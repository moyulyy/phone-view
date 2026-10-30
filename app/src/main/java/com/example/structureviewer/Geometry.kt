package com.example.structureviewer

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 两个向量的夹角（度） */
fun angleDeg(u: Vec3, v: Vec3): Double {
    val d = (u.dot(v) / (u.norm() * v.norm())).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(d))
}

/** 由晶格矢量计算晶胞参数 */
fun cellFromLattice(lat: List<Vec3>): Cell {
    return Cell(
        lat[0].norm(), lat[1].norm(), lat[2].norm(),
        angleDeg(lat[1], lat[2]), angleDeg(lat[0], lat[2]), angleDeg(lat[0], lat[1])
    )
}

/** 由晶胞参数构造晶格矢量（a 沿 x 轴，b 在 xy 平面） */
fun latticeFromCell(cell: Cell): List<Vec3> {
    val ca = cos(Math.toRadians(cell.alpha))
    val cb = cos(Math.toRadians(cell.beta))
    val cg = cos(Math.toRadians(cell.gamma))
    val sg = sin(Math.toRadians(cell.gamma))
    val ax = cell.a
    val ay = 0.0
    val az = 0.0
    val bx = cell.b * cg
    val by = cell.b * sg
    val bz = 0.0
    val cx = cell.c * cb
    val cy = cell.c * (ca - cb * cg) / sg
    val cz = sqrt(max(0.0, cell.c * cell.c - cx * cx - cy * cy))
    return listOf(Vec3(ax, ay, az), Vec3(bx, by, bz), Vec3(cx, cy, cz))
}

/** 分数坐标 → 笛卡尔坐标 */
fun fracToCart(f: Vec3, lat: List<Vec3>): Vec3 =
    lat[0] * f.x + lat[1] * f.y + lat[2] * f.z

/** 笛卡尔坐标 → 分数坐标 */
fun cartToFrac(c: Vec3, lat: List<Vec3>): Vec3 {
    val inv = inverse3(lat)
    val fx = c.x * inv[0].x + c.y * inv[1].x + c.z * inv[2].x
    val fy = c.x * inv[0].y + c.y * inv[1].y + c.z * inv[2].y
    val fz = c.x * inv[0].z + c.y * inv[1].z + c.z * inv[2].z
    return Vec3(fx, fy, fz)
}

/** 3x3 矩阵求逆（行向量为三个晶格矢量） */
fun inverse3(m: List<Vec3>): List<Vec3> {
    val a = arrayOf(
        doubleArrayOf(m[0].x, m[0].y, m[0].z),
        doubleArrayOf(m[1].x, m[1].y, m[1].z),
        doubleArrayOf(m[2].x, m[2].y, m[2].z)
    )
    val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
        a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
        a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
    if (abs(det) < 1e-12) throw IllegalArgumentException("晶格矩阵奇异，无法求逆")
    val inv = Array(3) { DoubleArray(3) }
    inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det
    inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det
    inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det
    inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det
    inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det
    inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det
    inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det
    inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det
    inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det
    return listOf(
        Vec3(inv[0][0], inv[0][1], inv[0][2]),
        Vec3(inv[1][0], inv[1][1], inv[1][2]),
        Vec3(inv[2][0], inv[2][1], inv[2][2])
    )
}

/** 把坐标折叠到 [0,1) */
fun wrap(v: Double): Double {
    var r = v % 1.0
    if (r < 0) r += 1.0
    return r
}
