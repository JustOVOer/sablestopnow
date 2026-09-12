#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建 "Aeronautics: Tweaks & Toolkit" 的 mod 图标场景。

几何全部取自真实模型文件（不臆造）：
  * 物理手杖 : Simulated  assets/simulated/models/item/creative_physics_staff/item.json
              贴图 assets/simulated/textures/item/zapper_staff.png   (16x16)
  * 钢铁齿轮 : Create     assets/create/models/block/cogwheel_shaftless.json
              贴图 assets/create/textures/block/cogwheel.png        (32x32)
  * 包裹立方 : 本脚本程序化生成 16x16 淡蓝贴图（cube_inner 实心 / cube_shell 负尺寸外壳）

坐标约定
--------
内部全部使用 **y-up** 空间（1 单位 = Blockbench 1 像素，16 = 1 方块）。
Java 模型源文件是 y-down：转换时 y 取负，并按 C·R·C⁻¹ 规则把 x/z 轴角度取负
（镜像空间下旋转矩阵的共轭结果；y 轴角度不变）。

输出
----
  branding/scene.txt                                扁平四边形列表（给 Java 渲染器）
  branding/aeronautics-tweaks-toolkit-icon.bbmodel  Blockbench 工程（free 格式）
  branding/textures/*.png                           用到的贴图
"""

import base64
import glob
import io
import json
import math
import os
import struct
import sys
import uuid
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRAND = os.path.join(ROOT, "branding")
TEXDIR = os.path.join(BRAND, "textures")

STAFF_MODEL = "assets/simulated/models/item/creative_physics_staff/item.json"
STAFF_TEX = "assets/simulated/textures/item/zapper_staff.png"
GEAR_MODEL = "assets/create/models/block/cogwheel_shaftless.json"
GEAR_TEX = "assets/create/textures/block/cogwheel.png"

CANVAS = 512
PADDING = 34

CUBE_INNER_RGB = (0x6F, 0xC8, 0xF0)
CUBE_SHELL_RGB = (0xA8, 0xE4, 0xFF)

FACE_ORDER = ("north", "south", "east", "west", "up", "down")


# ==========================================================================
# PNG 读写（不依赖 PIL）
# ==========================================================================
def png_write(path, w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def png_size(data):
    return struct.unpack(">II", data[16:24])


def make_inner_texture():
    w = h = 16
    buf = bytearray(w * h * 4)
    r, g, b = CUBE_INNER_RGB
    for y in range(h):
        for x in range(w):
            edge = min(x, y, w - 1 - x, h - 1 - y)
            k = 1.0 if edge >= 2 else (0.74 if edge == 1 else 0.52)
            hi = 1.16 if (x + y) < 9 else 1.0
            i = (y * w + x) * 4
            buf[i] = min(255, int(r * k * hi))
            buf[i + 1] = min(255, int(g * k * hi))
            buf[i + 2] = min(255, int(b * k * hi))
            buf[i + 3] = 255
    return w, h, buf


def make_shell_texture():
    w = h = 16
    buf = bytearray(w * h * 4)
    r, g, b = CUBE_SHELL_RGB
    for y in range(h):
        for x in range(w):
            edge = min(x, y, w - 1 - x, h - 1 - y)
            i = (y * w + x) * 4
            if edge == 0:
                col, alpha = (0xEC, 0xFA, 0xFF), 240
            elif edge == 1:
                col, alpha = (r, g, b), 165
            elif edge == 2:
                col, alpha = (r, g, b), 78
            else:
                col, alpha = (r, g, b), 36
            buf[i], buf[i + 1], buf[i + 2], buf[i + 3] = col[0], col[1], col[2], alpha
    return w, h, buf


# ==========================================================================
# 数学
# ==========================================================================
def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def rot_matrix(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mat_apply(m, p):
    return (m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2],
            m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2],
            m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2])


# y-down(Java) -> y-up 的旋转共轭：x/z 轴角度取负，y 轴不变
def source_rotation_matrix(axis, deg):
    if axis in ("x", "z"):
        deg = -deg
    return rot_matrix(axis, deg)


# ==========================================================================
# 载入 Java 方块模型
# ==========================================================================
def resolve_parent(parent, ns):
    if ":" in parent:
        ns, p = parent.split(":", 1)
    else:
        p = parent
    return "assets/%s/models/%s.json" % (ns, p)


def load_java_model(z, path, depth=0):
    d = json.loads(z.read(path).decode("utf-8"))
    ns = path.split("/")[1]
    merged = {"textures": {}, "elements": []}
    parent = d.get("parent")
    if parent and depth < 6 and not parent.startswith("builtin/"):
        try:
            pp = resolve_parent(parent, ns)
            if pp in z.namelist():
                merged = load_java_model(z, pp, depth + 1)
        except Exception:
            pass
    merged["textures"] = dict(merged["textures"])
    merged["textures"].update(d.get("textures") or {})
    merged["elements"] = list(merged["elements"]) + list(d.get("elements") or [])
    return merged


def uv_corners(u1, v1, u2, v2, rot):
    """MC 的 face uv rotation（顺时针 90 的倍数）-> 四个角的 uv（TL,TR,BR,BL）。"""
    tl, tr, br, bl = (u1, v1), (u2, v1), (u2, v2), (u1, v2)
    if rot == 90:
        return [bl, tl, tr, br]
    if rot == 180:
        return [br, bl, tl, tr]
    if rot == 270:
        return [tr, br, bl, tl]
    return [tl, tr, br, bl]


def cube_corners(x1, y1, z1, x2, y2, z2):
    """y-up 空间的六个面；每面 4 角按 uv 的 TL,TR,BR,BL 排列（源自 MC 的面 uv 约定）。"""
    ax1, ax2 = min(x1, x2), max(x1, x2)
    ay1, ay2 = min(y1, y2), max(y1, y2)
    az1, az2 = min(z1, z2), max(z1, z2)
    return {
        "north": [(ax2, ay2, az1), (ax1, ay2, az1), (ax1, ay1, az1), (ax2, ay1, az1)],
        "south": [(ax1, ay2, az2), (ax2, ay2, az2), (ax2, ay1, az2), (ax1, ay1, az2)],
        "east":  [(ax2, ay2, az2), (ax2, ay2, az1), (ax2, ay1, az1), (ax2, ay1, az2)],
        "west":  [(ax1, ay2, az1), (ax1, ay2, az2), (ax1, ay1, az2), (ax1, ay1, az1)],
        "up":    [(ax1, ay2, az1), (ax2, ay2, az1), (ax2, ay2, az2), (ax1, ay2, az2)],
        "down":  [(ax1, ay1, az2), (ax2, ay1, az2), (ax2, ay1, az1), (ax1, ay1, az1)],
    }


# ==========================================================================
# 一个「物件」：源元素 + 自身缩放/旋转/落点
# ==========================================================================
class Piece:
    def __init__(self, name, elements, texname, tex_size):
        self.name = name
        self.elements = elements          # Java 空间的元素（含自身 rotation）
        self.texname = texname
        self.tex_size = tex_size
        self.c0 = self._center_yup()
        self.scale = 1.0
        self.pos = (0.0, 0.0, 0.0)        # 最终中心 C
        self.rots = []                    # [("y", -25), ("z", 45)] 由内到外
        # 屏幕「上下镜像」：对渲染结果在屏幕空间做垂直翻转（像翻图片一样）
        self.flip_screen_y = False
        # 3D 反射式镜像（备用）：沿指定法线的平面反射，注意会把斜对相机的圆盘压平
        self.mirror_screen_up = False
        self.mirror_axis = (0.0, 1.0, 0.0)
        self.gid = 0

    # -- 源几何中心（y-up） --
    def _center_yup(self):
        lo = [1e9] * 3
        hi = [-1e9] * 3
        for el in self.elements:
            for (px, py, pz) in (el["from"], el["to"]):
                v = (px, -py, pz)
                for i in range(3):
                    lo[i] = min(lo[i], v[i])
                    hi[i] = max(hi[i], v[i])
        return tuple((lo[i] + hi[i]) / 2.0 for i in range(3))

    def rotation_matrix(self):
        m = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        for axis, deg in self.rots:       # 由内到外依次左乘
            m = mat_mul(rot_matrix(axis, deg), m)
        return m

    # -- 源点 -> 世界点 --
    def to_world(self, p_src_yup):
        m = self.rotation_matrix()
        lp = tuple((p_src_yup[i] - self.c0[i]) * self.scale for i in range(3))
        w = mat_apply(m, lp)
        return tuple(w[i] + self.pos[i] for i in range(3))

    def quads(self):
        """返回 (texname, [4 个世界点], [4 个 uv], 面名)。"""
        m = self.rotation_matrix()
        out = []
        for el in self.elements:
            faces = el.get("faces") or {}
            if not faces:
                continue
            fx1, fy1, fz1 = el["from"]
            fx2, fy2, fz2 = el["to"]
            corners = cube_corners(fx1, -fy1, fz1, fx2, -fy2, fz2)
            rot = el.get("rotation")
            rmat, rorg = None, None
            if rot and rot.get("angle"):
                o = rot.get("origin", el["from"])
                rmat = source_rotation_matrix(rot["axis"], rot["angle"])
                rorg = (o[0], -o[1], o[2])

            def place(p):
                if rmat:
                    d = (p[0] - rorg[0], p[1] - rorg[1], p[2] - rorg[2])
                    p = tuple(mat_apply(rmat, d)[i] + rorg[i] for i in range(3))
                lp = tuple((p[i] - self.c0[i]) * self.scale for i in range(3))
                w = mat_apply(m, lp)
                w = tuple(w[i] + self.pos[i] for i in range(3))
                if self.mirror_screen_up:
                    # 沿镜像平面法线做一次反射：w' = w - 2 (w·n) n，平面过物件中心
                    n = self.mirror_axis
                    d = sum((w[i] - self.pos[i]) * n[i] for i in range(3))
                    w = tuple(w[i] - 2.0 * d * n[i] for i in range(3))
                return w

            inv = self.tex_size / 16.0
            flags = (1 if self.mirror_screen_up else 0) | (2 if self.flip_screen_y else 0)
            for fname in FACE_ORDER:
                f = faces.get(fname)
                if not f:
                    continue
                u1, v1, u2, v2 = f["uv"]
                uvs = [(u * inv, v * inv)
                       for (u, v) in uv_corners(u1, v1, u2, v2, f.get("rotation", 0))]
                out.append((self.texname, [place(p) for p in corners[fname]], uvs, fname,
                            flags, self.gid))
        return out

    # -- 写进 .bbmodel 的元素（自身坐标已缩放/平移，旋转交给元素 rotation 字段）--
    def bb_elements(self):
        els = []
        for _i, el in enumerate(self.elements):
            fx1, fy1, fz1 = el["from"]
            fx2, fy2, fz2 = el["to"]
            a = self._local((fx1, -fy1, fz1))
            b = self._local((fx2, -fy2, fz2))
            # 让 from 是负尺寸写法（手杖 outer_cube 的语言）：若源模型 y 反向则保留
            frm = [min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2])]
            to = [max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2])]
            if fy1 > fy2:                 # 源元素是「负尺寸」写法 -> 保持同样的反转
                frm[1], to[1] = to[1], frm[1]
            rot = el.get("rotation") or {}
            e = {
                "name": "%s_%03d" % (self.name, _i),
                "from": [round(v, 4) for v in frm],
                "to": [round(v, 4) for v in to],
                "origin": [0, 0, 0],
                "uuid": str(uuid.uuid4()),
                "type": "cube",
                "color": 0,
                "inflate": 0,
                "faces": {},
                "visibility": {f: True for f in FACE_ORDER},
            }
            if rot.get("angle"):
                o = rot.get("origin", el["from"])
                lo = self._local((o[0], -o[1], o[2]))
                e["origin"] = [round(v, 4) for v in lo]
                ang = source_rotation_matrix(rot["axis"], rot["angle"])
                e["rotation"] = list(_euler_single(rot["axis"], ang))
            else:
                e["rotation"] = [0, 0, 0]
            for fname in FACE_ORDER:
                f = (el.get("faces") or {}).get(fname)
                if not f:
                    continue
                face = {"uv": list(f["uv"]), "texture": 0}
                if f.get("rotation"):
                    face["rotation"] = f["rotation"]
                e["faces"][fname] = face
            els.append(e)
        return els

    def _local(self, p_yup):
        return tuple((p_yup[i] - self.c0[i]) * self.scale + self.pos[i] for i in range(3))


def _euler_single(source_axis, m):
    """把单轴旋转矩阵写回 bbmodel 的 [rx,ry,rz]（共轭后 x/z 可能变号）。"""
    ang = {
        "x": math.degrees(math.atan2(m[2][1], m[1][1])),
        "y": math.degrees(math.atan2(m[0][2], m[0][0])),
        "z": math.degrees(math.atan2(m[1][0], m[0][0])),
    }
    # 判断实际生效的轴（共轭不改变轴，只改符号）
    v = {"x": [0, 0, 0], "y": [0, 0, 0], "z": [0, 0, 0]}
    v[source_axis] = round(ang[source_axis], 4)
    return v["x"], v["y"], v["z"]


# ==========================================================================
# 相机
# ==========================================================================
class Camera:
    def __init__(self, yaw, pitch):
        self.yaw, self.pitch = yaw, pitch
        self.m = mat_mul(rot_matrix("x", pitch), rot_matrix("y", yaw))

    def project(self, p):
        v = mat_apply(self.m, p)
        return (v[0], -v[1], v[2])

    def world_offset(self, sx, sy, sd=0.0):
        """把「屏幕意图」(右/上/靠近) 换算成世界偏移。

        约定：project() 里 screen_x = view.x = row0·p，screen_y = -view.y = -row1·p，
        深度 depth = view.z = row2·p（越大越靠近相机）。所以
        屏幕右 = row0，屏幕上 = row1，靠近 = +row2。
        """
        m = self.m
        right = (m[0][0], m[0][1], m[0][2])
        up = (m[1][0], m[1][1], m[1][2])
        back = (m[2][0], m[2][1], m[2][2])
        return tuple(sx * right[i] + sy * up[i] + sd * back[i] for i in range(3))


def make_wrapped_cube(name, tex_sizes, half=0.60, pad=0.42):
    """内实心小立方 + 负尺寸外壳（与手杖 outer_cube 同构）。"""
    els = []
    for tex, pad_ in (("cube_inner", 0.0), ("cube_shell", pad)):
        s = half + pad_
        els.append({
            "from": [-s, s, -s],
            "to": [s, -s, s],          # y 反写 = 负尺寸
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#t"} for f in FACE_ORDER},
            "_tex": tex,
        })
    pieces = []
    for el in els:
        p = Piece(name, [el], el["_tex"], tex_sizes[el["_tex"]])
        p.c0 = (0.0, 0.0, 0.0)
        pieces.append(p)
    return pieces


# ==========================================================================
# 主流程
# ==========================================================================
def main():
    os.makedirs(TEXDIR, exist_ok=True)

    aeronautics = glob.glob(os.path.join(ROOT, "run", "mods", "*aeronautics-bundled*.jar"))[0]
    create_path = glob.glob(os.path.join(ROOT, "run", "mods", "*create-1.21.1*.jar"))[0]

    with zipfile.ZipFile(aeronautics) as outer:
        nested = [n for n in outer.namelist() if n.endswith(".jar") and "simulated" in n.lower()][0]
        sim = zipfile.ZipFile(io.BytesIO(outer.read(nested)))
    create = zipfile.ZipFile(create_path)

    tex_sizes, tex_files = {}, {}
    for name, (z, path) in (("zapper_staff", (sim, STAFF_TEX)), ("cogwheel", (create, GEAR_TEX))):
        data = z.read(path)
        open(os.path.join(TEXDIR, name + ".png"), "wb").write(data)
        tex_sizes[name] = png_size(data)[0]
        tex_files[name] = data

    # 齿轮用「保形换色」后的钢铁版本（由 tools/Steelify.java 生成；缺失则退回原版木色）
    steel_path = os.path.join(TEXDIR, "cogwheel_steel.png")
    gear_tex = "cogwheel"
    if os.path.exists(steel_path):
        data = open(steel_path, "rb").read()
        tex_sizes["cogwheel_steel"] = png_size(data)[0]
        tex_files["cogwheel_steel"] = data
        gear_tex = "cogwheel_steel"
    for name, (w, h, buf) in (("cube_inner", make_inner_texture()),
                              ("cube_shell", make_shell_texture())):
        png_write(os.path.join(TEXDIR, name + ".png"), w, h, buf)
        tex_sizes[name] = w

    staff = Piece("staff", load_java_model(sim, STAFF_MODEL)["elements"], "zapper_staff",
                  tex_sizes["zapper_staff"])
    staff.scale = 1.0                            # 原尺寸：约 34.5 单位长，撑满图标对角线
    staff.pos = (0.0, 0.0, 0.0)
    # -135°：把手杖「翻过来」——带能量环/十字的头朝左上，杖尾朝右下
    staff.rots = [("z", -135.0)]

    gear = Piece("gear", load_java_model(create, GEAR_MODEL)["elements"], gear_tex,
                 tex_sizes[gear_tex])
    gear.scale = 0.88
    # 原角度立起 + 偏右上；再对整体做一次**屏幕上下镜像**（像翻图片一样，几何不变）
    gear.rots = [("x", -55.0), ("y", 25.0)]
    gear.flip_screen_y = True

    cam = Camera(yaw=-37.0, pitch=27.0)
    gear.mirror_axis = (cam.m[1][0], cam.m[1][1], cam.m[1][2])   # 相机的屏幕向上轴（备用）
    # 左下角空三角的内切位置：既尽量填满，又不与手杖相碰。
    # 深度给负值 = 比手杖**更远**，这样重叠处是手杖压在齿轮前面。
    gear.pos = cam.world_offset(-7.4, -7.4, -3.0)

    # ---- 小立方体：绕「手杖上端」的环形轨道（轨道平面垂直于手杖轴）----
    th = math.radians(-135.0)
    head_dir = (math.sin(th), -math.cos(th), 0.0)     # 指向手杖头部（左上）
    side_u = (head_dir[1], -head_dir[0], 0.0)         # 与轴垂直
    side_v = (0.0, 0.0, 1.0)                          # 与轴垂直
    half_len = 0.5 * 34.5 * staff.scale               # 手杖半长
    orbit_c = tuple(0.55 * half_len * head_dir[k] for k in range(3))   # 轨道中心：靠近上端
    orbit_r = 5.0

    cubes = []
    n_cubes = 8
    for i in range(n_cubes):
        phi = 2.0 * math.pi * (i / n_cubes) + 0.35
        jitter = 1.5 * (((i * 0.6180339887) % 1.0) - 0.5)              # 沿轴小幅错开
        pos = tuple(
            orbit_c[k]
            + orbit_r * (math.cos(phi) * side_u[k] + math.sin(phi) * side_v[k])
            + jitter * head_dir[k]
            for k in range(3))
        for p in make_wrapped_cube("cube%d" % i, tex_sizes):
            p.name = "%s_%s" % (p.name, p.texname.split("_")[-1])   # 名字唯一
            p.rots = [("y", math.degrees(phi) * 0.6)]
            p.pos = pos
            cubes.append(p)

    pieces = [staff, gear] + cubes
    for i, p in enumerate(pieces):
        p.gid = i

    # 调试/二分定位用：python build_scene.py [staff|gear|cubes] 只输出对应部分
    only = sys.argv[1] if len(sys.argv) > 1 else "all"
    if only != "all":
        pieces = [p for p in pieces
                  if (only == "staff" and p is staff)
                  or (only == "gear" and p is gear)
                  or (only == "cubes" and p in cubes)]

    # ---------------- 场景文件（给 Java 渲染器） ----------------
    quads = []
    for p in pieces:
        quads += p.quads()

    order = ["zapper_staff", gear_tex, "cube_inner", "cube_shell"]
    order = list(dict.fromkeys(order))
    lines = ["CANVAS %d %d" % (CANVAS, PADDING), "NTEX %d" % len(order)]
    for i, n in enumerate(order):
        lines.append("TEX %d %s" % (i, n))
    idx = {n: i for i, n in enumerate(order)}
    for (tex, pts, uvs, fname, flags, gid) in quads:
        lines.append("QUAD %d %s %d %d " % (idx[tex], fname, flags, gid)
                     + " ".join("%.5f" % v for p in pts for v in p) + " "
                     + " ".join("%.5f" % v for uv in uvs for v in uv))
    with open(os.path.join(BRAND, "scene.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    # ---------------- Blockbench 工程 ----------------
    bb = build_bbmodel(pieces, tex_sizes, tex_files)
    suffix = sys.argv[2] if len(sys.argv) > 2 else ""
    bb_name = ("aeronautics-tweaks-toolkit-icon.bbmodel" if only == "all"
               else "test-%s%s.bbmodel" % (only, ("-" + suffix) if suffix else ""))
    bb_path = os.path.join(BRAND if only == "all" else os.path.join(ROOT, ".dsh-tmp"), bb_name)
    with open(bb_path, "w", encoding="utf-8") as f:
        json.dump(bb, f, ensure_ascii=False, indent="\t")

    lo = [min(p[i] for (_, pts, _, _, _, _) in quads for p in pts) for i in range(3)]
    hi = [max(p[i] for (_, pts, _, _, _, _) in quads for p in pts) for i in range(3)]
    print("[scene] pieces=%d quads=%d" % (len(pieces), len(quads)))
    print("[scene] bbox lo=%s hi=%s" % ([round(v, 2) for v in lo], [round(v, 2) for v in hi]))
    print("[scene] wrote scene.txt, %s" % os.path.basename(bb_path))


def build_bbmodel(pieces, tex_sizes, tex_files):
    """生成 Blockbench 工程（free 格式）。

    ⚠ 贴图**不内嵌**（不写 base64 source）：贴图 png 放在 branding/textures/ 下，
    在 Blockbench 里自行拖入即可。
    """
    elements, outliner, textures = [], [], []
    texid = {}
    for i, name in enumerate(["zapper_staff", "cogwheel_steel", "cube_inner", "cube_shell"]):
        w = tex_sizes.get(name, 16)
        texid[name] = i
        textures.append({
            "path": "", "name": name + ".png", "folder": "item", "namespace": "",
            "id": str(i), "group": "", "width": w, "height": w, "uv_width": w, "uv_height": w,
            "particle": False, "use_as_default": False, "layers_enabled": False,
            "sync_to_project": "", "render_mode": "default", "render_sides": "auto",
            "frame_time": 1, "frame_order_type": "loop", "frame_order": "",
            "frame_interpolate": False, "visible": True, "internal": True, "saved": False,
            "uuid": str(uuid.uuid4()), "relative_path": "",
        })
    texid["cogwheel"] = texid["cogwheel_steel"]

    def group(name, origin, rotation, children, open_=True):
        return {
            "name": name, "origin": [round(v, 4) for v in origin],
            "color": 0, "uuid": str(uuid.uuid4()), "export": True, "mirror_uv": False,
            "isOpen": open_, "locked": False, "visibility": True, "autouv": 0,
            "rotation": [round(v, 4) for v in rotation], "children": children,
        }

    for p in pieces:
        uuids = []
        for el in p.bb_elements():
            el["faces"] = {k: dict(v, texture=texid[p.texname]) for k, v in el["faces"].items()}
            elements.append(el)
            uuids.append(el["uuid"])
        # 每个旋转轴包一层组（由内到外），全部以物件中心为轴心
        node = uuids
        for axis, deg in p.rots:
            rot = [0.0, 0.0, 0.0]
            rot["xyz".index(axis)] = deg
            node = [group("%s_%s" % (p.name, axis), p.pos, rot, node)]
        outliner.append(group(p.name, p.pos, [0.0, 0.0, 0.0], node))

    return {
        "meta": {
            "format_version": "5.0",
            "model_format": "free",
            "box_uv": False,
        },
        "name": "aeronautics-tweaks-toolkit-icon",
        "parent": "",
        "ambientocclusion": True,
        "front_gui_light": False,
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": outliner,
        "textures": textures,
        "display": {},
        "animations": [],
        "animation_controllers": [],
        "uv_groups": [],
    }


if __name__ == "__main__":
    main()
