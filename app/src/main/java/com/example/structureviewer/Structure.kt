package com.example.structureviewer

/** 三维向量 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = Math.sqrt(dot(this))
}

/** 晶胞参数（长度单位 Å，角度单位度） */
data class Cell(
    val a: Double,
    val b: Double,
    val c: Double,
    val alpha: Double,
    val beta: Double,
    val gamma: Double
) {
    val volume: Double
        get() {
            val ca = Math.cos(Math.toRadians(alpha))
            val cb = Math.cos(Math.toRadians(beta))
            val cg = Math.cos(Math.toRadians(gamma))
            val v = 1.0 - ca * ca - cb * cb - cg * cg + 2 * ca * cb * cg
            return a * b * c * Math.sqrt(v.coerceAtLeast(0.0))
        }
}

/** 一个原子（element 为元素符号，坐标为笛卡尔或分数坐标） */
data class Atom(val element: String, val x: Double, val y: Double, val z: Double)

/** 解析得到的结构 */
data class Structure(
    val format: String,
    val title: String = "",
    val cell: Cell? = null,
    val lattice: List<Vec3>? = null,
    val atoms: List<Atom> = emptyList(),
    val frac: List<Atom>? = null
)
