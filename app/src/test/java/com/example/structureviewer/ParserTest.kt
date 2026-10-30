package com.example.structureviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private val poscar = """
        TiO2 rutile
        1.0
        4.593 0.000 0.000
        0.000 4.593 0.000
        0.000 0.000 2.958
        Ti O
        1 2
        Direct
        0.000 0.000 0.000
        0.305 0.305 0.000
        0.695 0.695 0.000
    """.trimIndent()

    @Test
    fun vaspParsesAndConvertsToCif() {
        val st = VaspParser.parse(poscar)
        assertEquals("TiO2 rutile", st.title)
        assertEquals(3, st.atoms.size)
        assertEquals(listOf("Ti", "O", "O"), st.atoms.map { it.element })

        val cell = st.cell
        assertNotNull(cell)
        assertEquals(4.593, cell!!.a, 1e-6)
        assertEquals(4.593, cell.b, 1e-6)
        assertEquals(2.958, cell.c, 1e-6)
        assertEquals(90.0, cell.alpha, 1e-6)
        assertEquals(90.0, cell.gamma, 1e-6)

        // 分数坐标 0.305 在 a 轴上 → 0.305 * 4.593
        assertEquals(0.305 * 4.593, st.atoms[1].x, 1e-6)
        assertEquals(0.305 * 4.593, st.atoms[1].y, 1e-6)

        // 导出 CIF 并能被重新解析
        val cif = CifExporter.toCif(st)
        assertTrue(cif.contains("_cell_length_a"))
        assertTrue(cif.contains("_atom_site_fract_x"))
        val back = CifParser.parse(cif)
        assertEquals(3, back.atoms.size)
        assertNotNull(back.cell)
        assertEquals(4.593, back.cell!!.a, 1e-4)
        assertEquals(2.958, back.cell!!.c, 1e-4)
        assertEquals(0.305 * 4.593, back.atoms[1].x, 1e-3)
    }

    @Test
    fun vaspCartesianCoordinates() {
        val cart = """
            Si bulk
            1.0
            5.43 0.0 0.0
            0.0 5.43 0.0
            0.0 0.0 5.43
            Si
            2
            Cartesian
            0.0 0.0 0.0
            1.3575 1.3575 1.3575
        """.trimIndent()
        val st = VaspParser.parse(cart)
        assertEquals(2, st.atoms.size)
        assertEquals(1.3575, st.atoms[1].x, 1e-6)
        // 分数坐标 = 1.3575 / 5.43 = 0.25
        assertEquals(0.25, st.frac!![1].x, 1e-6)
        assertNotNull(st.cell)
        assertEquals(5.43, st.cell!!.a, 1e-6)
    }

    @Test
    fun vaspWithoutElementSymbols() {
        val countsOnly = """
            unknown
            1.0
            3.0 0.0 0.0
            0.0 3.0 0.0
            0.0 0.0 3.0
            1 2
            Direct
            0.0 0.0 0.0
            0.5 0.5 0.5
            0.25 0.25 0.25
        """.trimIndent()
        val st = VaspParser.parse(countsOnly)
        assertEquals(3, st.atoms.size)
        assertEquals("X1", st.atoms[0].element)
        assertEquals("X2", st.atoms[1].element)
    }

    @Test
    fun xyzParses() {
        val xyz = """
            3
            water molecule
            O   0.000   0.000   0.000
            H   0.758   0.586   0.000
            H  -0.758   0.586   0.000
        """.trimIndent()
        val st = XyzParser.parse(xyz)
        assertEquals("water molecule", st.title)
        assertEquals(3, st.atoms.size)
        assertEquals(listOf("O", "H", "H"), st.atoms.map { it.element })
        assertEquals(0.758, st.atoms[1].x, 1e-9)
    }

    @Test
    fun cifParsesWithUncertainties() {
        val cif = """
            data_NaCl
            _cell_length_a    5.640(2)
            _cell_length_b    5.640(2)
            _cell_length_c    5.640(2)
            _cell_angle_alpha 90.0
            _cell_angle_beta  90.0
            _cell_angle_gamma 90.0
            loop_
            _atom_site_label
            _atom_site_type_symbol
            _atom_site_fract_x
            _atom_site_fract_y
            _atom_site_fract_z
            Na1 Na 0.0 0.0 0.0
            Cl1 Cl 0.5 0.5 0.5
        """.trimIndent()
        val st = CifParser.parse(cif)
        assertEquals("NaCl", st.title)
        assertEquals(2, st.atoms.size)
        assertNotNull(st.cell)
        assertEquals(5.640, st.cell!!.a, 1e-9)
        assertEquals(90.0, st.cell!!.alpha, 1e-9)
        // Na 在原点，Cl 在体心 (2.82, 2.82, 2.82)
        assertEquals(2.82, st.atoms[1].x, 1e-6)
    }

    @Test
    fun cifWithCartesianCoordinates() {
        val cif = """
            data_test
            _cell_length_a 10.0
            _cell_length_b 10.0
            _cell_length_c 10.0
            _cell_angle_alpha 90
            _cell_angle_beta 90
            _cell_angle_gamma 90
            loop_
            _atom_site_label
            _atom_site_Cartn_x
            _atom_site_Cartn_y
            _atom_site_Cartn_z
            C1 1.0 2.0 3.0
        """.trimIndent()
        val st = CifParser.parse(cif)
        assertEquals(1, st.atoms.size)
        assertEquals(1.0, st.atoms[0].x, 1e-9)
        assertEquals(3.0, st.atoms[0].z, 1e-9)
    }

    @Test
    fun hexagonalCellVolume() {
        // 理想 hcp 晶胞 a=b=3.0, c=4.9, gamma=120
        val lat = latticeFromCell(Cell(3.0, 3.0, 4.9, 90.0, 90.0, 120.0))
        val c = cellFromLattice(lat)
        assertEquals(3.0, c.a, 1e-6)
        assertEquals(3.0, c.b, 1e-6)
        assertEquals(4.9, c.c, 1e-6)
        assertEquals(120.0, c.gamma, 1e-6)
        // V = a*b*c*sin(120)
        assertEquals(3.0 * 3.0 * 4.9 * Math.sin(Math.toRadians(120.0)), c.volume, 1e-6)
    }
}
