# 草图约束矩阵（自动生成，依据 SketchSolver 源码）

> 扫描 `SketchSolver.java` 得到：kind 编号与"是否吃常量"严格来自源码；
> 操作数个数请以 `SketchSolver.operandCount(kind)` 为准（该表不是简单 switch，故不在此重复抄写以免走样）。

| kind | 名称 | 类别 | 吃常量 |
| --- | --- | --- | --- |
| 1 | FIXED | 尺寸 | 是 |
| 2 | COINCIDENT | 几何关系 | 否 |
| 3 | DISTANCE | 尺寸 | 是 |
| 4 | HORIZONTAL | 几何关系 | 否 |
| 5 | VERTICAL | 几何关系 | 否 |
| 6 | EQUAL_LEN | 几何关系 | 否 |
| 7 | MIDPOINT | 几何关系 | 否 |
| 8 | RADIUS | 尺寸 | 是 |
| 9 | PARALLEL | 几何关系 | 否 |
| 10 | PERPENDICULAR | 几何关系 | 否 |
| 11 | POINT_LINE_DISTANCE | 尺寸 | 是 |
| 12 | HPOINT_LINE_DISTANCE | 尺寸 | 是 |
| 13 | VPOINT_LINE_DISTANCE | 尺寸 | 是 |
| 14 | PARALLEL_LINES_DISTANCE | 尺寸 | 是 |
| 15 | HORIZONTAL_DISTANCE | 尺寸 | 是 |
| 16 | VERTICAL_DISTANCE | 尺寸 | 是 |
| 17 | SYMMETRIC | 几何关系 | 否 |
| 18 | SCALAR_EQUAL | 几何关系 | 否 |
| 19 | ARC_RADIUS | 尺寸 | 是 |
| 20 | ARC_LENGTH | 尺寸 | 是 |
| 21 | ARC_ANGLE | 尺寸 | 是 |
| 22 | LINES_ANGLE | 尺寸 | 是 |
| 23 | LINE_TANGENT_CIRCLE | 几何关系 | 否 |
| 24 | CIRCLE_TANGENT_CIRCLE | 几何关系 | 否 |
| 25 | POINTS_AT_ANGLE | 尺寸 | 是 |
| 26 | POINT_ARC_COINCIDENT | 几何关系 | 否 |

## 图元配对速查（与 SolidWorks 对齐的常用约束）

| 选择 | 可加约束 | 说明 |
| --- | --- | --- |
| 直线 + 直线 | PARALLEL(9) / PERPENDICULAR(10) / EQUAL_LEN(6) / LINES_ANGLE(22) / PARALLEL_LINES_DISTANCE(14) | 平行、垂直、等长、夹角、线线距离 |
| 直线 + 圆/弧 | LINE_TANGENT_CIRCLE(23) | 相切 |
| 圆/弧 + 圆/弧 | CIRCLE_TANGENT_CIRCLE(24) | 相切（同/内切） |
| 点 + 点 | COINCIDENT(2) / HORIZONTAL_DISTANCE(15) / VERTICAL_DISTANCE(16) / POINTS_AT_ANGLE(25) | 重合、水平/竖直距离、点间角度 |
| 点 + 直线 | MIDPOINT(7) / POINT_LINE_DISTANCE(11) / HPOINT_LINE_DISTANCE(12) / VPOINT_LINE_DISTANCE(13) | 中点、点到线距离 |
| 点 + 圆/弧 | COINCIDENT(2) / POINT_ARC_COINCIDENT(26) | 点在圆周/弧上 |
| 单体 | FIXED(1) / HORIZONTAL(4) / VERTICAL(5) / DISTANCE(3) / RADIUS(8) / ARC_RADIUS(19) / ARC_LENGTH(20) / ARC_ANGLE(21) | 固定、水平、竖直、长度、半径、弧长、弧角 |

## 待办（补 UI 入口）
- 共线（COLLINEAR）当前求解器**没有**独立 kind；可用"两头点分别 COINCIDENT 到对方直线"表达，菜单里需要合成一个入口。
- 等长（EQUAL_LEN/SCALAR_EQUAL(18)）求解器支持，需确认左栏是否已给按钮。
- 同心：用圆心与圆心的 COINCIDENT(2) 表达，菜单需要合成"同心"入口。
