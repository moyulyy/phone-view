package com.example.structureviewer

import java.util.Locale

/** 把解析出的结构导出为标准 CIF 文本（供 3Dmol 显示） */
object CifExporter {

    fun toCif(s: Structure): String {
        val cell = s.cell ?: throw IllegalArgumentException("缺少晶胞信息")
        val frac = s.frac ?: throw IllegalArgumentException("缺少分数坐标")

        val sb = StringBuilder()
        sb.append("data_generated\n")
        sb.append("_cell_length_a    ").append(fmt(cell.a)).append('\n')
        sb.append("_cell_length_b    ").append(fmt(cell.b)).append('\n')
        sb.append("_cell_length_c    ").append(fmt(cell.c)).append('\n')
        sb.append("_cell_angle_alpha ").append(fmt(cell.alpha)).append('\n')
        sb.append("_cell_angle_beta  ").append(fmt(cell.beta)).append('\n')
        sb.append("_cell_angle_gamma ").append(fmt(cell.gamma)).append('\n')
        sb.append("_cell_volume      ").append(fmt(cell.volume)).append('\n')
        sb.append("loop_\n")
        sb.append("_atom_site_label\n")
        sb.append("_atom_site_type_symbol\n")
        sb.append("_atom_site_fract_x\n")
        sb.append("_atom_site_fract_y\n")
        sb.append("_atom_site_fract_z\n")
        sb.append("_atom_site_occupancy\n")

        var idx = 1
        for (a in frac) {
            sb.append(a.element).append(idx)
                .append("  ").append(a.element)
                .append("  ").append(fmt(a.x))
                .append("  ").append(fmt(a.y))
                .append("  ").append(fmt(a.z))
                .append("  1.0\n")
            idx++
        }
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.6f", v)
}
