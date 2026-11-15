package com.mine.myjizhang.excel

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 轻量 xlsx 写入器（无需 POI）。
 * 所有单元格以 inline string 写入，金额列同样按文本单元格输出，跨设备打开不乱码。
 */
object XlsxWriter {

    fun write(out: OutputStream, sheets: LinkedHashMap<String, List<List<String?>>>) {
        ZipOutputStream(out).use { zip ->

            val sheetNames = sheets.keys.toList()
            val overrideXml = sheetNames.indices.joinToString("\n") { i ->
                "<Override PartName=\"/xl/worksheets/sheet" + (i + 1) +
                    ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            }
            val sheetListXml = sheetNames.mapIndexed { i, name ->
                "<sheet name=\"" + escape(name) + "\" sheetId=\"" + (i + 1) +
                    "\" r:id=\"rId" + (i + 1) + "\"/>"
            }.joinToString("\n")
            val sheetRelsXml = sheetNames.indices.joinToString("\n") { i ->
                "<Relationship Id=\"rId" + (i + 1) +
                    "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet" +
                    (i + 1) + ".xml\"/>"
            }

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
""" + overrideXml + "\n</Types>").toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
""" + sheetListXml + "\n</sheets>\n</workbook>").toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
""" + sheetRelsXml +
                    "\n<Relationship Id=\"rId" + (sheets.size + 1) +
                    "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n</Relationships>")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            sheets.entries.toList().forEachIndexed { idx, entry ->
                val name = entry.key
                val rows = entry.value
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet" + (idx + 1) + ".xml"))
                zip.write(buildSheetXml(rows).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun buildSheetXml(rows: List<List<String?>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
        sb.append("<sheetData>\n")
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"").append(r + 1).append("\">")
            row.forEachIndexed { c, v ->
                if (v != null && v.isNotEmpty()) {
                    sb.append("<c r=\"").append(colName(c)).append(r + 1)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(v)).append("</t></is></c>")
                }
            }
            sb.append("</row>\n")
        }
        sb.append("</sheetData>\n</worksheet>")
        return sb.toString()
    }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun fmtTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}
