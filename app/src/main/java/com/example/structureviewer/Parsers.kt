package com.example.structureviewer

import java.util.Locale

/** VASP POSCAR / CONTCAR 解析器 */
object VaspParser {

    fun parse(text: String): Structure {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 8) throw IllegalArgumentException("POSCAR/CONTCAR 内容不完整")

        var i = 0
        val title = lines[i++]
        val scaleRaw = lines[i++].toDoubleOrNull()
            ?: throw IllegalArgumentException("第 2 行缩放因子无法解析")

        val a1 = parseVec(lines[i++])
        val a2 = parseVec(lines[i++])
        val a3 = parseVec(lines[i++])

        // 缩放因子为负时表示晶格矢量为实际值（体积 = -scale）
        val scale = if (scaleRaw >= 0.0) scaleRaw else 1.0
        val lattice = listOf(a1 * scale, a2 * scale, a3 * scale)

        // 第 6 行：元素符号 或 各类原子数目
        var elements: MutableList<String>
        var counts: MutableList<Int>
        val tokens6 = lines[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
        val isNumeric = tokens6.isNotEmpty() && tokens6.all { it.toDoubleOrNull() != null }
        if (isNumeric) {
            counts = tokens6.map { it.toInt() }.toMutableList()
            elements = (1..counts.size).map { "X$it" }.toMutableList()
            i++
        } else {
            elements = tokens6.toMutableList()
            i++
            counts = lines[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
                .map { it.toInt() }.toMutableList()
            i++
        }

        // 可选 "Selective dynamics" 行
        if (i < lines.size && lines[i].lowercase(Locale.US).startsWith("s")) {
            i++
        }

        // 坐标类型行（可选）：Direct / Cartesian / C / D / K
        var fractional = true
        if (i < lines.size) {
            val tok = lines[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tok.size == 1 && tok[0].lowercase(Locale.US) in
                setOf("direct", "cartesian", "cart", "c", "d", "k")
            ) {
                fractional = tok[0].lowercase(Locale.US).startsWith("d")
                i++
            }
            // 否则该行就是第一条坐标（VASP 缺省为 Direct）
        }

        val total = counts.sum()
        val coords = ArrayList<Vec3>(total)
        while (i < lines.size && coords.size < total) {
            coords.add(parseVec(lines[i]))
            i++
        }
        if (coords.size < total) {
            throw IllegalArgumentException("坐标行数不足（需要 $total 行，实际 ${coords.size} 行）")
        }

        val cartesian = ArrayList<Atom>(total)
        val fractionalAtoms = ArrayList<Atom>(total)
        var idx = 0
        for (sp in counts.indices) {
            val el = elements.getOrElse(sp) { "X" }
            for (k in 0 until counts[sp]) {
                val f = coords[idx]
                val c = if (fractional) {
                    fracToCart(f, lattice)
                } else {
                    Vec3(f.x * scale, f.y * scale, f.z * scale)
                }
                cartesian.add(Atom(el, c.x, c.y, c.z))
                val fr = if (fractional) f else cartToFrac(c, lattice)
                fractionalAtoms.add(Atom(el, wrap(fr.x), wrap(fr.y), wrap(fr.z)))
                idx++
            }
        }

        val cell = cellFromLattice(lattice)
        return Structure("POSCAR/CONTCAR", title, cell, lattice, cartesian, fractionalAtoms)
    }

    private fun parseVec(line: String): Vec3 {
        val t = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.size < 3) throw IllegalArgumentException("坐标行格式错误: $line")
        return Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble())
    }
}

/** XYZ 解析器（取第一帧） */
object XyzParser {

    fun parse(text: String): Structure {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw IllegalArgumentException("文件为空")

        val n = lines[0].split(Regex("\\s+")).filter { it.isNotEmpty() }
            .firstOrNull()?.toIntOrNull()
            ?: throw IllegalArgumentException("XYZ 首行应为原子数目")

        var i = 1
        var title = ""
        if (i < lines.size) {
            val t = lines[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
            val looksLikeAtom = t.size >= 4 &&
                t[1].toDoubleOrNull() != null &&
                t[2].toDoubleOrNull() != null &&
                t[3].toDoubleOrNull() != null
            if (!looksLikeAtom) {
                title = lines[i]
                i++
            }
        }

        val atoms = ArrayList<Atom>()
        while (i < lines.size && atoms.size < n) {
            val t = lines[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (t.size >= 4) {
                val x = t[1].toDoubleOrNull()
                val y = t[2].toDoubleOrNull()
                val z = t[3].toDoubleOrNull()
                if (x != null && y != null && z != null) {
                    atoms.add(Atom(CifParser.cleanElement(t[0]), x, y, z))
                }
            }
            i++
        }

        return Structure("XYZ", title, null, null, atoms, null)
    }
}

/** CIF 解析器（用于摘要信息；3D 显示交给 3Dmol 直接解析原始 CIF） */
object CifParser {

    fun parse(text: String): Structure {
        val lines = text.lines()

        var dataName = ""
        var cellA = 0.0; var cellB = 0.0; var cellC = 0.0
        var cellAlpha = 90.0; var cellBeta = 90.0; var cellGamma = 90.0
        var haveCell = false

        var loopTags: MutableList<String>? = null
        var rows = mutableListOf<List<String>>()
        val atomSites = mutableListOf<Map<String, String>>()

        fun applyCellKey(tag: String, value: String) {
            val v = cifDouble(value) ?: return
            when (tag) {
                "_cell_length_a" -> { cellA = v; haveCell = true }
                "_cell_length_b" -> { cellB = v; haveCell = true }
                "_cell_length_c" -> { cellC = v; haveCell = true }
                "_cell_angle_alpha" -> cellAlpha = v
                "_cell_angle_beta" -> cellBeta = v
                "_cell_angle_gamma" -> cellGamma = v
            }
        }

        fun finalizeLoop() {
            val tags = loopTags
            if (tags != null && rows.isNotEmpty()) {
                val hasCoords = tags.any {
                    it == "_atom_site_fract_x" || it == "_atom_site_cartn_x"
                }
                if (hasCoords) {
                    for (r in rows) {
                        val m = mutableMapOf<String, String>()
                        for (k in tags.indices) {
                            if (k < r.size) m[tags[k]] = r[k]
                        }
                        atomSites.add(m)
                    }
                }
            }
            loopTags = null
            rows = mutableListOf()
        }

        for (raw0 in lines) {
            val raw = raw0.trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue

            if (raw.equals("loop_", ignoreCase = true)) {
                finalizeLoop()
                loopTags = mutableListOf()
                continue
            }
            if (raw.startsWith("data_")) {
                finalizeLoop()
                dataName = raw.removePrefix("data_").trim()
                continue
            }
            if (raw.startsWith("stop_") || raw.startsWith("global_") || raw.startsWith("save_")) {
                finalizeLoop()
                continue
            }
            if (raw.startsWith("_")) {
                val tokens = tokenize(raw)
                if (tokens.isEmpty()) continue
                val tag = tokens[0].lowercase(Locale.US)
                val value = tokens.subList(1, tokens.size).joinToString(" ")
                val tags = loopTags
                if (tags != null && rows.isEmpty()) {
                    tags.add(tag)
                } else {
                    if (tags != null) finalizeLoop()
                    applyCellKey(tag, value)
                }
                continue
            }
            // 数据行
            if (loopTags != null) {
                val tokens = tokenize(raw)
                if (tokens.isNotEmpty()) rows.add(tokens)
            }
        }
        finalizeLoop()

        val cell: Cell? =
            if (haveCell) Cell(cellA, cellB, cellC, cellAlpha, cellBeta, cellGamma) else null
        val lattice = cell?.let { latticeFromCell(it) }

        val cartesian = ArrayList<Atom>()
        val frac = ArrayList<Atom>()
        for (m in atomSites) {
            val el = cleanElement(m["_atom_site_type_symbol"] ?: deriveElement(m["_atom_site_label"]))
            val fx = m["_atom_site_fract_x"]?.let { cifDouble(it) }
            val fy = m["_atom_site_fract_y"]?.let { cifDouble(it) }
            val fz = m["_atom_site_fract_z"]?.let { cifDouble(it) }
            if (fx != null && fy != null && fz != null) {
                frac.add(Atom(el, wrap(fx), wrap(fy), wrap(fz)))
                if (lattice != null) {
                    val c = fracToCart(Vec3(fx, fy, fz), lattice)
                    cartesian.add(Atom(el, c.x, c.y, c.z))
                }
            } else {
                val cx = m["_atom_site_cartn_x"]?.let { cifDouble(it) }
                val cy = m["_atom_site_cartn_y"]?.let { cifDouble(it) }
                val cz = m["_atom_site_cartn_z"]?.let { cifDouble(it) }
                if (cx != null && cy != null && cz != null) {
                    cartesian.add(Atom(el, cx, cy, cz))
                    if (lattice != null) {
                        val f = cartToFrac(Vec3(cx, cy, cz), lattice)
                        frac.add(Atom(el, wrap(f.x), wrap(f.y), wrap(f.z)))
                    }
                }
            }
        }

        return Structure("CIF", dataName, cell, lattice, cartesian, if (frac.isNotEmpty()) frac else null)
    }

    /** 按 CIF 引号规则切分一行 */
    fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuote != null) {
                if (ch == inQuote) inQuote = null else sb.append(ch)
            } else {
                when {
                    ch == '\'' || ch == '"' -> inQuote = ch
                    ch == ' ' || ch == '\t' -> {
                        if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.setLength(0) }
                    }
                    else -> sb.append(ch)
                }
            }
            i++
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /** 去掉 CIF 数值中的不确定度括号，如 4.593(2) → 4.593 */
    fun cifDouble(s: String): Double? {
        return s.replace(Regex("\\([^)]*\\)"), "").trim().toDoubleOrNull()
    }

    fun cleanElement(s: String): String {
        val m = Regex("^[A-Za-z]{1,2}").find(s.trim())
        val sym = m?.value ?: "X"
        return sym.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
    }

    fun deriveElement(label: String?): String {
        if (label.isNullOrBlank()) return "X"
        return cleanElement(label)
    }
}
