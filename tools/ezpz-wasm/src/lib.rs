//! C-ABI shim: EZPZ (Rust) → WebAssembly, callable from Java via Chicory.
//!
//! ## Why a hand-written C ABI instead of wasm-bindgen
//!
//! Chicory (pure-Java WASM runtime) can only call *raw* wasm exports and read/write
//! linear memory. `wasm-bindgen` generates JS glue plus JS-type marshalling that has
//! no meaning on the JVM, so this shim exposes a deliberately primitive interface:
//! flat `f64`/`i32` arrays passed through linear memory.
//!
//! ## ABI conventions (frozen — the Java side depends on these)
//!
//! * Coordinates and scalars are `f64`; counts, indices and handles are `i32`.
//! * All exports return `i32`: `0` = ok, negative = error code.
//! * Errors via [`sketch_last_error`] / [`sketch_last_error_len`] (UTF-8, not
//!   NUL-terminated, buffer owned by the shim, valid until the next call).
//! * Memory handed to Java comes from [`sketch_alloc`], returns via [`sketch_free`].
//!
//! ### Variables, and why the Java side owns them
//!
//! A sketch has a flat array of scalar **variables** (one per coordinate / radius).
//! Java allocates the array, writes initial values, and the solver overwrites it
//! in place with the solution. Because EZPZ's `SolveOutcome::final_values()` returns
//! values *in the same order as the initial guesses*, variable index `i` maps 1:1 to
//! `Id` number `i` — no id juggling crosses the boundary.
//!
//! ### Constraint program encoding
//!
//! Constraints travel as one flat `i32` program: each constraint is a `kind` tag
//! followed by a kind-specific number of variable indices. Constants (`f64`:
//! distances, radii) are read from a separate `f64` array, consumed in order.
//!
//! | kind | name       | operands                          | const |
//! |-----:|------------|-----------------------------------|-------|
//! | 1    | FIXED      | `v`                               | yes   |
//! | 2    | COINCIDENT | `ax ay bx by`                     | no    |
//! | 3    | DISTANCE   | `ax ay bx by`                     | yes   |
//! | 4    | HORIZONTAL | `ax ay bx by`                     | no    |
//! | 5    | VERTICAL   | `ax ay bx by`                     | no    |
//! | 6    | EQUAL_LEN  | `a0x a0y a1x a1y b0x b0y b1x b1y` | no    |
//! | 7    | MIDPOINT   | `ax ay bx by mx my`               | no    |
//! | 8    | RADIUS     | `cx cy r`                         | yes   |
//! | 9    | PARALLEL   | `a0x a0y a1x a1y b0x b0y b1x b1y` | no    |
//! | 10   | PERPENDICULAR | same as PARALLEL               | no    |
//! | 11   | POINT_LINE_DISTANCE | `px py ax ay bx by`      | yes   |
//! | 12   | HPOINT_LINE_DISTANCE | same as 11              | yes   |
//! | 13   | VPOINT_LINE_DISTANCE | same as 11              | yes   |
//! | 14   | PARALLEL_LINES_DISTANCE | `a0x..b1y` (8)       | yes   |
//! | 15   | HORIZONTAL_DISTANCE | `ax ay bx by`            | yes   |
//! | 16   | VERTICAL_DISTANCE | `ax ay bx by`              | yes   |
//! | 17   | SYMMETRIC  | `ax ay bx by px py qx qy`         | no    |
//! | 18   | SCALAR_EQUAL | `s0 s1`                         | no    |
//! | 19   | ARC_RADIUS | `cx cy sx sy ex ey`                 | yes   |
//! | 20   | ARC_LENGTH | same as 19                          | yes   |
//! | 21   | ARC_ANGLE  | same as 19 (const = **degrees**)    | yes   |
//! | 22   | LINES_ANGLE | `a0x..b1y` (8, const = degrees)    | yes   |
//! | 23   | LINE_TANGENT_CIRCLE | `ax ay bx by cx cy r`      | no    |
//! | 24   | CIRCLE_TANGENT_CIRCLE | `c0x c0y r0 c1x c1y r1`  | no    |
//! | 25   | POINTS_AT_ANGLE | `px py qx qy rx ry` (const = degrees) | yes |

use core::slice;
use std::alloc::{alloc, dealloc, Layout};

use ezpz::datatypes::inputs::{DatumCircle, DatumCircularArc, DatumDistance, DatumLineSegment, DatumPoint};
use ezpz::datatypes::{Angle, AngleKind};
use ezpz::{CircleSide, LineSide};
use ezpz::{solve, Config, Constraint, ConstraintRequest, Id, IdGenerator};

/// ABI version. Bumped whenever exported signatures or memory layout change, so the
/// Java side can refuse to run against a stale `.wasm`.
const ABI_VERSION: i32 = 4;

/// Alignment for every buffer crossing the ABI boundary. `f64` needs 8.
const ABI_ALIGN: usize = 8;

/// Pointer width crossing the ABI.
///
/// The Java-facing contract is defined by the **wasm32** build, where `usize` is
/// 32-bit and these parameters therefore appear as `i32` to Chicory. Using `usize`
/// rather than a hard-coded `i32` keeps the *native* `cargo test` build honest:
/// casting a 64-bit heap pointer to `i32` truncates it and segfaults
/// (STATUS_ACCESS_VIOLATION), which is exactly how this was caught.
type Ptr = usize;

/// Capacity of the fixed error buffer. A fixed buffer keeps the pointer stable and
/// sidesteps `static mut Vec` aliasing hazards.
const ERR_CAP: usize = 512;

/// Most recent error message (UTF-8, not NUL-terminated). Never reallocated.
static mut ERR_BUF: [u8; ERR_CAP] = [0; ERR_CAP];
/// Length of the message currently in `ERR_BUF`.
static mut ERR_LEN: i32 = 0;

// ---------------------------------------------------------------------------
// error / memory plumbing
// ---------------------------------------------------------------------------

/// Record `message` as the current error and return `code` for convenient tail calls.
fn fail(code: i32, message: &str) -> i32 {
    let bytes = message.as_bytes();
    let n = if bytes.len() > ERR_CAP { ERR_CAP } else { bytes.len() };
    // SAFETY: single-threaded wasm instance; writes go through raw pointers from
    // `addr_of_mut!`, so no `&mut` to the static is ever created.
    unsafe {
        let dst = core::ptr::addr_of_mut!(ERR_BUF) as *mut u8;
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), dst, n);
        core::ptr::addr_of_mut!(ERR_LEN).write(n as i32);
    }
    code
}

/// Allocate `len` bytes for Java to write into. Returns a pointer, or `0` on failure.
#[no_mangle]
pub extern "C" fn sketch_alloc(len: i32) -> Ptr {
    if len <= 0 {
        return 0;
    }
    // SAFETY: `len > 0` and the alignment is a power of two.
    unsafe {
        let layout = Layout::from_size_align_unchecked(len as usize, ABI_ALIGN);
        alloc(layout) as Ptr
    }
}

/// Release memory previously handed out by [`sketch_alloc`].
///
/// # Safety
/// `ptr`/`len` must be exactly the pair from an earlier `sketch_alloc`, freed once.
#[no_mangle]
pub unsafe extern "C" fn sketch_free(ptr: Ptr, len: i32) {
    if ptr == 0 || len <= 0 {
        return;
    }
    let layout = Layout::from_size_align_unchecked(len as usize, ABI_ALIGN);
    dealloc(ptr as *mut u8, layout);
}

/// ABI version of the loaded module; the Java side checks this on startup.
#[no_mangle]
pub extern "C" fn sketch_abi_version() -> i32 {
    ABI_VERSION
}

/// Initialise the module. **Java must call this once, right after instantiation.**
///
/// <h2>wasm SIMD：踩过的四个坑，别再试错</h2>
/// faer（EZPZ 的线性代数后端）在 wasm 上会执行 `simd128` 指令（`V128_LOAD` 等），
/// 而 Chicory 的**默认解释器不认识它们**，会抛
/// `Machine doesn't recognize Instruction ... V128_LOAD`。以下三条路都**试过且无效**：
///
/// 1. `rustc --print cfg` 显示 `wasm32-unknown-unknown` 默认不开 simd128 —— 但没用，
///    因为 gemm 不按 target feature 判定，它有一个常量 `DEFAULT_WASM_SIMD128 = true`。
/// 2. `-C target-feature=-simd128` 重编 —— 无效，.wasm 体积分毫不变（SIMD 内核照旧发出）。
/// 3. `pulp-wasm-simd-flag::disable_simd128()` —— 无效，那只管 pulp，不管 gemm。
/// 4. 换 Chicory 的 SIMD 机器（`SimdInterpreterMachine`）—— 能跑，但需要
///    `--add-modules jdk.incubator.vector`，而**我们没法要求玩家加 JVM 参数**，所以放弃。
///
/// **真正有效的开关**是 gemm 自己提供的运行期 setter（见下）。关掉之后 gemm 走
/// `scalar::gemm_basic`，数值结果不变，只是慢一些 —— 对交互式草图求解完全够用。
///
/// 顺带：这条路径不需要 `com.dylibso.chicory:simd`，也不需要 incubator 模块。
#[no_mangle]
pub extern "C" fn sketch_init() -> i32 {
    // ⚠ 必须在第一次 solve 之前调用。两边都在**每次派发时**读各自的原子标志，
    // 所以只要设置得比第一次求解早就行。
    //
    // ⚠⚠ 必须**两个都关**。它们来自两套独立的 SIMD 实现，只关一个，另一个照样执行 V128_*：
    //   * pulp  —— faer 的 SIMD 抽象层
    //   * gemm  —— faer 矩阵乘的后端（默认 DEFAULT_WASM_SIMD128 = true）
    // 实测：只关 pulp 仍报 V128_LOAD；只关 gemm 也仍报 V128_LOAD。
    #[cfg(target_family = "wasm")]
    {
        pulp_wasm_simd_flag::disable_simd128();
        gemm_common::set_wasm_simd128(false);
    }
    ABI_VERSION
}

/// Pointer to the most recent error message (UTF-8, not NUL-terminated).
#[no_mangle]
pub extern "C" fn sketch_last_error() -> Ptr {
    core::ptr::addr_of!(ERR_BUF) as Ptr
}

/// Byte length of the most recent error message.
#[no_mangle]
pub extern "C" fn sketch_last_error_len() -> i32 {
    // SAFETY: plain read of a primitive static; no reference is created.
    unsafe { core::ptr::addr_of!(ERR_LEN).read() }
}

// ---------------------------------------------------------------------------
// constraint decoding
// ---------------------------------------------------------------------------

/// Number of `i32` operands following a constraint's `kind` tag.
///
/// Returns `None` for an unknown kind so the caller can report it.
fn operand_count(kind: i32) -> Option<usize> {
    match kind {
        1 => Some(1), // FIXED      v
        2 => Some(4), // COINCIDENT ax ay bx by
        3 => Some(4), // DISTANCE   ax ay bx by
        4 => Some(4), // HORIZONTAL ax ay bx by
        5 => Some(4), // VERTICAL   ax ay bx by
        6 => Some(8), // EQUAL_LEN  a0x a0y a1x a1y b0x b0y b1x b1y
        7 => Some(6), // MIDPOINT   ax ay bx by mx my
        8 => Some(3), // RADIUS     cx cy r
        9 => Some(8), // PARALLEL   a0x a0y a1x a1y b0x b0y b1x b1y
        10 => Some(8), // PERPENDICULAR (same operands as PARALLEL)
        11 => Some(6), // POINT_LINE_DISTANCE  px py ax ay bx by
        12 => Some(6), // HPOINT_LINE_DISTANCE (same)
        13 => Some(6), // VPOINT_LINE_DISTANCE (same)
        14 => Some(8), // PARALLEL_LINES_DISTANCE a0x..b1y
        15 => Some(4), // HORIZONTAL_DISTANCE   ax ay bx by
        16 => Some(4), // VERTICAL_DISTANCE     ax ay bx by
        17 => Some(8), // SYMMETRIC  ax ay bx by px py qx qy
        18 => Some(2), // SCALAR_EQUAL  s0 s1
        19 => Some(6), // ARC_RADIUS   cx cy sx sy ex ey
        20 => Some(6), // ARC_LENGTH   (same)
        21 => Some(6), // ARC_ANGLE    (same, const = degrees)
        22 => Some(8), // LINES_ANGLE  a0x a0y a1x a1y b0x b0y b1x b1y
        23 => Some(7), // LINE_TANGENT_CIRCLE  ax ay bx by cx cy r
        24 => Some(6), // CIRCLE_TANGENT_CIRCLE c0x c0y r0 c1x c1y r1
        25 => Some(6), // POINTS_AT_ANGLE  px py qx qy rx ry
        _ => None,
    }
}

/// Does a constraint of this kind consume one `f64` constant?
fn takes_const(kind: i32) -> bool {
    matches!(kind, 1 | 3 | 8 | 11 | 12 | 13 | 14 | 15 | 16 | 19 | 20 | 21 | 22 | 25)
}

/// A cursor over the constraint program that panics-free validates indices.
struct Decoder<'a> {
    program: &'a [i32],
    consts: &'a [f64],
    pos: usize,
    const_pos: usize,
}

impl<'a> Decoder<'a> {
    fn next_i32(&mut self, var_count: i32) -> Result<i32, i32> {
        if self.pos >= self.program.len() {
            return Err(fail(-2, "constraint program ended mid-constraint"));
        }
        let v = self.program[self.pos];
        self.pos += 1;
        if v < 0 || v >= var_count {
            return Err(fail(-3, "constraint references a variable index out of range"));
        }
        Ok(v)
    }

    fn next_f64(&mut self) -> Result<f64, i32> {
        if self.const_pos >= self.consts.len() {
            return Err(fail(-4, "constraint constants exhausted"));
        }
        let v = self.consts[self.const_pos];
        self.const_pos += 1;
        Ok(v)
    }
}

/// Build a `DatumPoint` from two adjacent variable indices.
fn point(ids: &[Id], x: i32, y: i32) -> DatumPoint {
    DatumPoint::new_xy(ids[x as usize], ids[y as usize])
}

/// Build a `DatumLineSegment` from four adjacent variable indices.
fn circle(ids: &[Id], cx: i32, cy: i32, r: i32) -> DatumCircle {
    DatumCircle {
        center: point(ids, cx, cy),
        radius: DatumDistance { id: ids[r as usize] },
    }
}

fn arc(ids: &[Id], cx: i32, cy: i32, sx: i32, sy: i32, ex: i32, ey: i32) -> DatumCircularArc {
    DatumCircularArc {
        center: point(ids, cx, cy),
        start: point(ids, sx, sy),
        end: point(ids, ex, ey),
    }
}

fn line(ids: &[Id], ax: i32, ay: i32, bx: i32, by: i32) -> DatumLineSegment {
    DatumLineSegment::new(point(ids, ax, ay), point(ids, bx, by))
}

// ---------------------------------------------------------------------------
// solve
// ---------------------------------------------------------------------------

/// Solve a sketch.
///
/// * `vars_ptr` / `var_count` — in/out `f64` array of variables: initial guesses in,
///   solution out (same order).
/// * `cons_ptr` / `cons_len` — the flat `i32` constraint program (see module docs).
/// * `consts_ptr` — `f64` constants, consumed in constraint order.
/// * `out_info_ptr` — `i32[4]` out: `[satisfied, converged, iterations, unsatisfied]`.
///
/// Returns `0` on success (even when the system merely fails to converge — inspect
/// `out_info`), or a negative error code for malformed input.
///
/// # Safety
/// All pointers must reference the described numbers of readable/writable elements.
#[no_mangle]
pub unsafe extern "C" fn sketch_solve(
    vars_ptr: Ptr,
    var_count: i32,
    cons_ptr: Ptr,
    cons_len: i32,
    consts_ptr: Ptr,
    consts_len: i32,
    out_info_ptr: Ptr,
) -> i32 {
    if vars_ptr == 0 || var_count <= 0 {
        return fail(-1, "sketch_solve: no variables");
    }
    if out_info_ptr == 0 {
        return fail(-1, "sketch_solve: no out_info buffer");
    }
    let vars = slice::from_raw_parts_mut(vars_ptr as *mut f64, var_count as usize);
    let program: &[i32] = if cons_len <= 0 {
        &[]
    } else if cons_ptr == 0 {
        return fail(-1, "sketch_solve: null constraint program");
    } else {
        slice::from_raw_parts(cons_ptr as *const i32, cons_len as usize)
    };
    let consts: &[f64] = if consts_ptr == 0 {
        &[]
    } else {
        slice::from_raw_parts(consts_ptr as *const f64, consts_len.max(0) as usize)
    };

    // Variable index i  <->  Id number i. See module docs for why this is exact.
    let mut id_gen = IdGenerator::default();
    let ids: Vec<Id> = (0..var_count).map(|_| id_gen.next_id()).collect();

    // Decode the program into EZPZ constraints.
    let mut requests: Vec<ConstraintRequest> = Vec::new();
    let mut dec = Decoder { program, consts, pos: 0, const_pos: 0 };
    while dec.pos < program.len() {
        let kind = dec.program[dec.pos];
        dec.pos += 1;
        let Some(n) = operand_count(kind) else {
            return fail(-5, "unknown constraint kind");
        };
        let mut ops = [0i32; 8];
        for slot in ops.iter_mut().take(n) {
            match dec.next_i32(var_count) {
                Ok(v) => *slot = v,
                Err(code) => return code,
            }
        }
        let mut constant = 0.0f64;
        if takes_const(kind) {
            match dec.next_f64() {
                Ok(v) => constant = v,
                Err(code) => return code,
            }
        }
        // Kind 14 expands to TWO upstream constraints (parallel + point-line distance).
        if kind == 14 {
            let a = line(&ids, ops[0], ops[1], ops[2], ops[3]);
            let b = line(&ids, ops[4], ops[5], ops[6], ops[7]);
            for c in Constraint::parallel_lines_distance([a, b], constant) {
                requests.push(ConstraintRequest::highest_priority(c));
            }
            continue;
        }
        let constraint = match kind {
            1 => Constraint::Fixed(ids[ops[0] as usize], constant),
            2 => Constraint::PointsCoincident(point(&ids, ops[0], ops[1]), point(&ids, ops[2], ops[3])),
            3 => Constraint::Distance(point(&ids, ops[0], ops[1]), point(&ids, ops[2], ops[3]), constant),
            4 => Constraint::Horizontal(line(&ids, ops[0], ops[1], ops[2], ops[3])),
            5 => Constraint::Vertical(line(&ids, ops[0], ops[1], ops[2], ops[3])),
            6 => Constraint::LinesEqualLength(
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                line(&ids, ops[4], ops[5], ops[6], ops[7]),
            ),
            7 => Constraint::Midpoint(
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                point(&ids, ops[4], ops[5]),
            ),
            8 => Constraint::CircleRadius(
                ezpz::datatypes::inputs::DatumCircle {
                    center: point(&ids, ops[0], ops[1]),
                    radius: DatumDistance { id: ids[ops[2] as usize] },
                },
                constant,
            ),
            9 => Constraint::lines_parallel([
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                line(&ids, ops[4], ops[5], ops[6], ops[7]),
            ]),
            10 => Constraint::lines_perpendicular([
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                line(&ids, ops[4], ops[5], ops[6], ops[7]),
            ]),
            11 => Constraint::PointLineDistance(
                point(&ids, ops[0], ops[1]),
                line(&ids, ops[2], ops[3], ops[4], ops[5]),
                constant,
            ),
            12 => Constraint::HorizontalPointLineDistance(
                point(&ids, ops[0], ops[1]),
                line(&ids, ops[2], ops[3], ops[4], ops[5]),
                constant,
            ),
            13 => Constraint::VerticalPointLineDistance(
                point(&ids, ops[0], ops[1]),
                line(&ids, ops[2], ops[3], ops[4], ops[5]),
                constant,
            ),
            15 => Constraint::HorizontalDistance(
                point(&ids, ops[0], ops[1]),
                point(&ids, ops[2], ops[3]),
                constant,
            ),
            16 => Constraint::VerticalDistance(
                point(&ids, ops[0], ops[1]),
                point(&ids, ops[2], ops[3]),
                constant,
            ),
            17 => Constraint::Symmetric(
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                point(&ids, ops[4], ops[5]),
                point(&ids, ops[6], ops[7]),
            ),
            18 => Constraint::ScalarEqual(ids[ops[0] as usize], ids[ops[1] as usize]),
            19 => Constraint::ArcRadius(arc(&ids, ops[0], ops[1], ops[2], ops[3], ops[4], ops[5]), constant),
            20 => Constraint::ArcLength(arc(&ids, ops[0], ops[1], ops[2], ops[3], ops[4], ops[5]), constant),
            21 => Constraint::ArcAngle(
                arc(&ids, ops[0], ops[1], ops[2], ops[3], ops[4], ops[5]),
                Angle::from_degrees(constant),
            ),
            22 => Constraint::LinesAtAngle(
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                line(&ids, ops[4], ops[5], ops[6], ops[7]),
                AngleKind::Other(Angle::from_degrees(constant)),
            ),
            23 => Constraint::LineTangentToCircle(
                line(&ids, ops[0], ops[1], ops[2], ops[3]),
                circle(&ids, ops[4], ops[5], ops[6]),
                LineSide::Undefined,
            ),
            24 => Constraint::CircleTangentToCircle(
                circle(&ids, ops[0], ops[1], ops[2]),
                circle(&ids, ops[3], ops[4], ops[5]),
                CircleSide::Undefined,
            ),
            25 => Constraint::PointsAtAngle(
                point(&ids, ops[0], ops[1]),
                point(&ids, ops[2], ops[3]),
                point(&ids, ops[4], ops[5]),
                AngleKind::Other(Angle::from_degrees(constant)),
            ),
            _ => return fail(-5, "unknown constraint kind"),
        };
        // Everything from the UI is a hard requirement, so highest priority.
        requests.push(ConstraintRequest::highest_priority(constraint));
    }

    // Initial guesses must be in variable order for `final_values()` to line up.
    let guesses: Vec<(Id, f64)> = ids.iter().copied().zip(vars.iter().copied()).collect();

    let info = slice::from_raw_parts_mut(out_info_ptr as *mut i32, 4);
    match solve(&requests, guesses, Config::default()) {
        Ok(solution) => {
            let values = solution.final_values();
            if values.len() != vars.len() {
                return fail(-6, "solver returned a different number of variables");
            }
            vars.copy_from_slice(values);
            info[0] = if solution.is_satisfied() { 1 } else { 0 };
            info[1] = if solution.converged() { 1 } else { 0 };
            info[2] = solution.iterations() as i32;
            info[3] = solution.unsatisfied().len() as i32;
            0
        }
        Err(e) => {
            // A structurally unsolvable system: leave the initial guesses in place and
            // report it through the info buffer rather than as a hard error.
            info[0] = 0;
            info[1] = 0;
            info[2] = -1;
            info[3] = -1;
            fail(-7, &format!("ezpz failed: {}", e.error))
        }
    }
}

/// Toolchain self-test: reads `count` f64 values from `ptr`, returns their sum.
///
/// Proves the round-trip works (JVM → linear memory → wasm → JVM) independently of
/// the solver. Returns `NaN` and records an error on bad input.
///
/// # Safety
/// `ptr` must point to `count` readable `f64` values.
#[no_mangle]
pub unsafe extern "C" fn sketch_selftest_sum(ptr: Ptr, count: i32) -> f64 {
    if ptr == 0 || count <= 0 {
        fail(-1, "sketch_selftest_sum: null pointer or non-positive count");
        return f64::NAN;
    }
    let values = slice::from_raw_parts(ptr as *const f64, count as usize);
    values.iter().sum()
}
