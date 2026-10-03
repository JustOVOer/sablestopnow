//! Offline contract tests for the `sketch_*` C ABI.
//!
//! These run natively (`cargo test`) against the *exact same* exported functions the
//! JVM calls through Chicory, so they pin the ABI without needing Minecraft running.
//! If these pass and the JVM smoke test passes, the only remaining variable is the
//! wasm codegen itself — which is why the milestone keeps both.

use ezpz_wasm::*;

/// Constraint kinds, mirrored from the module docs.
const FIXED: i32 = 1;
const DISTANCE: i32 = 3;
const HORIZONTAL: i32 = 4;
const VERTICAL: i32 = 5;
const EQUAL_LEN: i32 = 6;
const MIDPOINT: i32 = 7;
const COINCIDENT: i32 = 2;

/// Run the solver over `vars` with the given program/consts; returns `(info, vars)`.
fn solve(vars: &mut [f64], program: &[i32], consts: &[f64]) -> ([i32; 4], ()) {
    let mut info = [0i32; 4];
    let rc = unsafe {
        sketch_solve(
            vars.as_mut_ptr() as usize,
            vars.len() as i32,
            program.as_ptr() as usize,
            program.len() as i32,
            consts.as_ptr() as usize,
            consts.len() as i32,
            info.as_mut_ptr() as usize,
        )
    };
    assert_eq!(rc, 0, "sketch_solve returned {}", rc);
    (info, ())
}

fn assert_close(actual: f64, expected: f64, label: &str) {
    assert!(
        (actual - expected).abs() < 1e-6,
        "{}: expected {}, got {}",
        label,
        expected,
        actual
    );
}

/// A horizontal line of length 4 anchored at the origin.
///
/// Fully determined, so the solution is unique up to the sign of q.x — and the
/// initial guess (+x) picks the positive branch.
#[test]
fn horizontal_line_of_known_length() {
    // vars: p.x p.y q.x q.y
    let mut vars = [0.0, 0.0, 0.1, 0.1];
    let program = [
        FIXED, 0, // p.x = 0
        FIXED, 1, // p.y = 0
        HORIZONTAL, 0, 1, 2, 3, // p-q is horizontal
        DISTANCE, 0, 1, 2, 3, // |p-q| = 4
    ];
    let consts = [0.0, 0.0, 4.0];
    let (info, _) = solve(&mut vars, &program, &consts);

    assert_eq!(info[0], 1, "expected a satisfied system, info={:?}", info);
    assert_close(vars[0], 0.0, "p.x");
    assert_close(vars[1], 0.0, "p.y");
    assert_close(vars[3], 0.0, "q.y (horizontal)");
    assert_close(vars[2].abs(), 4.0, "q.x (distance)");
}

/// A vertical line: the same shape rotated, to prove HORIZONTAL/VERTICAL are not
/// accidentally the same code path.
#[test]
fn vertical_line_of_known_length() {
    // vars: p.x p.y q.x q.y
    let mut vars = [0.0, 0.0, 0.1, 0.1];
    let program = [
        FIXED, 0, //
        FIXED, 1, //
        VERTICAL, 0, 1, 2, 3, //
        DISTANCE, 0, 1, 2, 3, //
    ];
    let consts = [0.0, 0.0, 3.0];
    let (info, _) = solve(&mut vars, &program, &consts);

    assert_eq!(info[0], 1, "expected a satisfied system, info={:?}", info);
    assert_close(vars[2], 0.0, "q.x (vertical)");
    assert_close(vars[3].abs(), 3.0, "q.y (distance)");
}

/// Two segments forced to equal length, one of them dimensioned.
#[test]
fn equal_length_propagates_the_dimension() {
    // vars: a0x a0y a1x a1y  b0x b0y b1x b1y
    let mut vars = [0.0, 0.0, 5.0, 0.0, 0.0, 2.0, 3.0, 2.0];
    let program = [
        FIXED, 0, //
        FIXED, 1, //
        FIXED, 2, // a1x = 5  => |a| = 5
        FIXED, 3, //
        HORIZONTAL, 0, 1, 2, 3, //
        EQUAL_LEN, 0, 1, 2, 3, 4, 5, 6, 7, // |a| == |b|
    ];
    let consts = [0.0, 0.0, 5.0, 0.0];
    let (info, _) = solve(&mut vars, &program, &consts);

    assert_eq!(info[0], 1, "expected a satisfied system, info={:?}", info);
    let len_a = ((vars[2] - vars[0]).powi(2) + (vars[3] - vars[1]).powi(2)).sqrt();
    let len_b = ((vars[6] - vars[4]).powi(2) + (vars[7] - vars[5]).powi(2)).sqrt();
    assert_close(len_a, 5.0, "|a|");
    assert_close(len_b, len_a, "|b| == |a|");
}

/// A midpoint constraint pulls the marker onto the segment's centre.
#[test]
fn midpoint_lands_on_the_centre() {
    // vars: ax ay bx by  mx my
    let mut vars = [0.0, 0.0, 4.0, 2.0, 0.5, 0.5];
    let program = [
        FIXED, 0, //
        FIXED, 1, //
        FIXED, 2, //
        FIXED, 3, //
        MIDPOINT, 0, 1, 2, 3, 4, 5, //
    ];
    let consts = [0.0, 0.0, 4.0, 2.0];
    let (info, _) = solve(&mut vars, &program, &consts);

    assert_eq!(info[0], 1, "expected a satisfied system, info={:?}", info);
    assert_close(vars[4], 2.0, "m.x");
    assert_close(vars[5], 1.0, "m.y");
}

/// Coincidence makes two points share a location.
#[test]
fn coincidence_merges_two_points() {
    // vars: ax ay bx by
    let mut vars = [1.0, 1.0, 9.0, 9.0];
    let program = [
        FIXED, 0, // a.x = 1
        FIXED, 1, // a.y = 1
        COINCIDENT, 0, 1, 2, 3, //
    ];
    let consts = [1.0, 1.0];
    let (info, _) = solve(&mut vars, &program, &consts);

    assert_eq!(info[0], 1, "expected a satisfied system, info={:?}", info);
    assert_close(vars[2], 1.0, "b.x");
    assert_close(vars[3], 1.0, "b.y");
}

/// No constraints at all must be a no-op that echoes the initial guesses, not a crash.
#[test]
fn empty_program_echoes_the_guesses() {
    let mut vars = [1.5, -2.25];
    let (info, _) = solve(&mut vars, &[], &[]);
    assert_close(vars[0], 1.5, "v0");
    assert_close(vars[1], -2.25, "v1");
    assert_eq!(info[0], 1, "an unconstrained system is trivially satisfied");
}

/// A malformed program must be rejected with a negative code and a readable message,
/// never a panic (a wasm trap would take the whole game down).
#[test]
fn out_of_range_variable_is_rejected() {
    let mut vars = [0.0, 0.0];
    let mut info = [0i32; 4];
    let program = [FIXED, 7]; // variable 7 does not exist
    let consts = [0.0];
    let rc = unsafe {
        sketch_solve(
            vars.as_mut_ptr() as usize,
            vars.len() as i32,
            program.as_ptr() as usize,
            program.len() as i32,
            consts.as_ptr() as usize,
            consts.len() as i32,
            info.as_mut_ptr() as usize,
        )
    };
    assert_eq!(rc, -3, "expected the out-of-range code");
    let msg = unsafe {
        let p = sketch_last_error() as *const u8;
        let n = sketch_last_error_len() as usize;
        String::from_utf8_lossy(std::slice::from_raw_parts(p, n)).into_owned()
    };
    assert!(msg.contains("out of range"), "unhelpful message: {msg}");
}

/// The generic memory round-trip. If this fails, nothing else can work.
#[test]
fn memory_round_trip() {
    let n = 4i32;
    let bytes = n * 8;
    let ptr = sketch_alloc(bytes);
    assert_ne!(ptr, 0, "allocation failed");
    let values = [1.0f64, 2.0, 3.5, -4.25];
    unsafe {
        std::ptr::copy_nonoverlapping(values.as_ptr(), ptr as *mut f64, n as usize);
        let sum = sketch_selftest_sum(ptr, n);
        assert_close(sum, 2.25, "sum");
        sketch_free(ptr, bytes);
    }
}

/// The version handshake the Java side performs on startup.
#[test]
fn abi_version_is_reported() {
    assert_eq!(sketch_abi_version(), 2);
}
