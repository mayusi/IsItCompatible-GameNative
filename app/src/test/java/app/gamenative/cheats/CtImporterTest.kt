package app.gamenative.cheats

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CtImporter] — the Cheat Engine `.CT` XML -> [CheatTable] importer.
 *
 * Covers the three load-bearing cases from the spec:
 *   1. A POINTER-CHAIN entry -> ChainSpec with the offsets reversed/redistributed per the
 *      verified [chain_resolve] direction (CE innermost offset == our valueOffset).
 *   2. A STATIC module+offset entry -> ChainSpec with empty offsets (recipe kind "static").
 *   3. An AUTO-ASSEMBLER (script) entry that can't be salvaged -> skipped WITH a reason.
 * Plus the type/address skip rules (Double, bare-absolute).
 */
class CtImporterTest {

    private val sampleCt = """
        <?xml version="1.0" encoding="utf-8"?>
        <CheatTable>
          <CheatEntries>
            <CheatEntry>
              <ID>1</ID>
              <Description>"Player Health (pointer)"</Description>
              <VariableType>4 Bytes</VariableType>
              <Address>game.exe+0xABC</Address>
              <Offsets>
                <Offset>10</Offset>
                <Offset>20</Offset>
                <Offset>30</Offset>
              </Offsets>
            </CheatEntry>
            <CheatEntry>
              <ID>2</ID>
              <Description>"Gold (static)"</Description>
              <VariableType>4 Bytes</VariableType>
              <Address>game.exe+0x123456</Address>
            </CheatEntry>
            <CheatEntry>
              <ID>3</ID>
              <Description>"Stamina (float pointer)"</Description>
              <VariableType>Float</VariableType>
              <Address>game.exe+0x500</Address>
              <Offsets>
                <Offset>8</Offset>
              </Offsets>
            </CheatEntry>
            <CheatEntry>
              <ID>4</ID>
              <Description>"Infinite Ammo (AA script)"</Description>
              <VariableType>Auto Assembler Script</VariableType>
              <AssemblerScript>[ENABLE]
        alloc(newmem,2048)
        label(returnhere)
        aobscanmodule(ammoSite, game.exe, 89 41 10 8B 50 14)
        newmem:
        mov [rcx+10],eax
        registersymbol(ammoSite)
        [DISABLE]
        </AssemblerScript>
            </CheatEntry>
            <CheatEntry>
              <ID>5</ID>
              <Description>"Position X (double, unsupported)"</Description>
              <VariableType>Double</VariableType>
              <Address>game.exe+0x900</Address>
            </CheatEntry>
            <CheatEntry>
              <ID>6</ID>
              <Description>"Bare absolute (no module)"</Description>
              <VariableType>4 Bytes</VariableType>
              <Address>01AB30C0</Address>
            </CheatEntry>
          </CheatEntries>
        </CheatTable>
    """.trimIndent()

    @Test
    fun `import maps pointer-chain static and float and skips AA-script double bare-absolute`() {
        val result = CtImporter.import(
            xmlBytes = sampleCt.toByteArray(Charsets.UTF_8),
            source = GameSource.STEAM,
            gameId = "999999",
            title = "Test Game",
        )
        assertNotNull("import returned null for valid XML", result)
        result!!

        // 3 importable (pointer health, static gold, float stamina); 3 skipped (AA, double, bare).
        assertEquals(3, result.importedCount)
        assertEquals(3, result.skippedCount)

        assertEquals(GameSource.STEAM, result.table.source)
        assertEquals("999999", result.table.gameId)
    }

    @Test
    fun `pointer-chain offsets follow chain_resolve direction - CE innermost is our valueOffset`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        val health = result.table.cheats.first { it.name == "Player Health (pointer)" }

        assertEquals("pointer_chain", health.recipe.kind)
        val chain = health.chain!!
        assertEquals("game.exe", chain.module)
        assertEquals(0xABCL, chain.base)
        // CE offsets [0x10, 0x20, 0x30] (innermost-first):
        //   valueOffset = CE first  = 0x10
        //   ourOffsets  = drop(1).reversed() = [0x30, 0x20]
        // so chain_resolve does: ptr=*(mod+0xABC); ptr=*(ptr+0x30); ptr=*(ptr+0x20); value=ptr+0x10
        assertEquals(0x10L, chain.valueOffset)
        assertEquals(listOf(0x30L, 0x20L), chain.offsets)
    }

    @Test
    fun `single-offset float pointer maps offset to valueOffset with empty deref list`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        val stamina = result.table.cheats.first { it.name == "Stamina (float pointer)" }

        assertEquals("f32", stamina.vtype)
        // A single CE offset [0x8] -> valueOffset=0x8, offsets=[] (no intermediate deref).
        val chain = stamina.chain!!
        assertEquals(0x8L, chain.valueOffset)
        assertTrue(chain.offsets.isEmpty())
        // With empty offsets it is effectively a one-deref pointer; kind is "static" only when
        // there are zero offsets — which is the case here.
        assertEquals("static", stamina.recipe.kind)
    }

    @Test
    fun `static module+offset entry has empty offsets and static kind`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        val gold = result.table.cheats.first { it.name == "Gold (static)" }

        assertEquals("static", gold.recipe.kind)
        val chain = gold.chain!!
        assertEquals("game.exe", chain.module)
        assertEquals(0x123456L, chain.base)
        assertTrue(chain.offsets.isEmpty())
        assertEquals(0L, chain.valueOffset)
    }

    @Test
    fun `AA script that needs code injection is skipped with a reason`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        // The AA script uses alloc/newmem/registersymbol -> not salvageable -> skipped.
        val aaSkip = result.skipped.firstOrNull { it.description.contains("Infinite Ammo") }
        assertNotNull("AA-script entry should be in the skip list", aaSkip)
        assertTrue(
            "skip reason should mention script/Auto-Assembler",
            aaSkip!!.reason.contains("Auto-Assembler", true) || aaSkip.reason.contains("script", true),
        )
        // It must NOT have been imported as a cheat.
        assertNull(result.table.cheats.firstOrNull { it.name.contains("Infinite Ammo") })
    }

    @Test
    fun `double type is skipped with an unsupported-type reason`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        val dblSkip = result.skipped.firstOrNull { it.description.contains("Position X") }
        assertNotNull(dblSkip)
        assertTrue(dblSkip!!.reason.contains("type", true) || dblSkip.reason.contains("Double", true))
    }

    @Test
    fun `bare absolute address with no module is skipped`() {
        val result = CtImporter.import(sampleCt.toByteArray(), GameSource.STEAM, "999999", "Test")!!
        val bareSkip = result.skipped.firstOrNull { it.description.contains("Bare absolute") }
        assertNotNull(bareSkip)
        assertTrue(bareSkip!!.reason.contains("absolute", true) || bareSkip.reason.contains("module", true))
    }

    @Test
    fun `salvageable AA script with a simple aobscan maps to an aob_freeze cheat`() {
        // A script that ONLY does an aobscan and references the label (no alloc/injection) is
        // salvaged into an aob_freeze cheat using the new aobScan round-trip.
        val xml = """
            <CheatTable><CheatEntries>
              <CheatEntry>
                <Description>"Simple AOB freeze"</Description>
                <VariableType>4 Bytes</VariableType>
                <AssemblerScript>[ENABLE]
        aobscanmodule(hpSite, game.exe, 48 8B 05 ?? ?? ?? ?? 89 41 10)
        hpSite+07:
        [DISABLE]
        </AssemblerScript>
              </CheatEntry>
            </CheatEntries></CheatTable>
        """.trimIndent()

        val result = CtImporter.import(xml.toByteArray(), GameSource.STEAM, "111", "T")!!
        assertEquals(1, result.importedCount)
        val cheat = result.table.cheats.first()
        assertEquals("aob_freeze", cheat.recipe.kind)
        assertNotNull(cheat.aob)
        // Wildcards normalized to "??", offset extracted from "hpSite+07:".
        assertEquals("48 8B 05 ?? ?? ?? ?? 89 41 10", cheat.aob!!.pattern)
        assertEquals(0x07, cheat.aob!!.offset)
    }

    @Test
    fun `oversize file is rejected with null`() {
        val big = ByteArray(CtImporter.MAX_CT_BYTES + 1)
        assertNull(CtImporter.import(big, GameSource.STEAM, "1", "T"))
    }

    @Test
    fun `malformed individual entry is skipped, rest still import`() {
        val xml = """
            <CheatTable><CheatEntries>
              <CheatEntry>
                <Description>"Good static"</Description>
                <VariableType>4 Bytes</VariableType>
                <Address>game.exe+0x10</Address>
              </CheatEntry>
              <CheatEntry>
                <Description>"Complex expr (bracketed)"</Description>
                <VariableType>4 Bytes</VariableType>
                <Address>[game.exe+0x10]+0x20</Address>
              </CheatEntry>
            </CheatEntries></CheatTable>
        """.trimIndent()

        val result = CtImporter.import(xml.toByteArray(), GameSource.STEAM, "1", "T")!!
        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals("Good static", result.table.cheats.first().name)
    }
}
