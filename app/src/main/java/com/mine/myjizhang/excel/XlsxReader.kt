package com.mine.myjizhang.excel

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 轻量 xlsx 读取器（无需 POI）。
 * 返回 LinkedHashMap<sheet名, 行列表>，单元格为文本，空单元格为 null。
 */
object XlsxReader {

    fun read(input: InputStream): LinkedHashMap<String, MutableList<MutableList<String?>>> {
        val raw = input.readBytes()
        val sheetNames = mutableListOf<String>()
        val sheetRelIds = mutableListOf<String>()
        val relIdToTarget = HashMap<String, String>()
        val sharedStrings = mutableListOf<String>()
        val worksheetData = HashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/workbook.xml" -> parseWorkbook(zip, sheetNames, sheetRelIds)
                    "xl/_rels/workbook.xml.rels" -> parseRels(zip, relIdToTarget)
                    "xl/sharedStrings.xml" -> parseSharedStrings(zip, sharedStrings)
                    else -> {
                        if (entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml")) {
                            worksheetData[entry.name] = zip.readBytes()
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }

        val sheets = LinkedHashMap<String, MutableList<MutableList<String?>>>()
        sheetNames.forEachIndexed { i, name ->
            val relId = sheetRelIds.getOrNull(i) ?: return@forEachIndexed
            val target = relIdToTarget[relId] ?: return@forEachIndexed
            val path = "xl/" + target.removePrefix("/")
            val data = worksheetData[path] ?: return@forEachIndexed
            sheets[name] = parseSheet(data, sharedStrings)
        }
        return sheets
    }

    private fun parseWorkbook(stream: InputStream, names: MutableList<String>, relIds: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        var relId = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "sheet") {
                    names.add(parser.getAttributeValue(null, "name") ?: "Sheet${names.size + 1}")
                    relId = parser.getAttributeValue(relNs, "id") ?: ""
                }
                XmlPullParser.END_TAG -> if (parser.name == "sheet") relIds.add(relId)
            }
            parser.next()
        }
    }

    private fun parseRels(stream: InputStream, out: HashMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = target
            }
            parser.next()
        }
    }

    private fun parseSharedStrings(stream: InputStream, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var inT = false
        var inSi = false
        val sb = StringBuilder()
        var current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "t" -> { inT = true; sb.setLength(0) }
                }
                XmlPullParser.TEXT -> if (inT) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> { inT = false; current.append(sb) }
                    "si" -> { if (inSi) out.add(current.toString()); inSi = false }
                }
            }
            parser.next()
        }
    }

    private fun parseSheet(data: ByteArray, sharedStrings: List<String>): MutableList<MutableList<String?>> {
        val rows = mutableListOf<MutableList<String?>>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), null)

        var row: MutableList<String?>? = null
        var cellType = ""
        var cellRef = ""
        var cellHasInline = false
        var inV = false
        var inInlineT = false
        val vSb = StringBuilder()
        val tSb = StringBuilder()
        var expectingCol = 0

        fun finishCell() {
            val r = row ?: return
            var col = colIndexFromRef(cellRef)
            if (col < 0) col = expectingCol
            while (r.size < col) r.add(null)
            val text: String? = when (cellType) {
                "s" -> {
                    val idx = vSb.toString().trim().toIntOrNull()
                    idx?.let { sharedStrings.getOrNull(it) }
                }
                "inlineStr" -> if (tSb.isEmpty()) null else tSb.toString()
                else -> {
                    val t = vSb.toString()
                    if (t.isEmpty()) null else t
                }
            }
            while (r.size < col) r.add(null)
            if (r.size == col) r.add(text) else r[col] = text
            expectingCol = col + 1
            vSb.setLength(0); tSb.setLength(0)
            inV = false; inInlineT = false; cellHasInline = false
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> { row = mutableListOf(); expectingCol = 0 }
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        cellRef = parser.getAttributeValue(null, "r") ?: ""
                        vSb.setLength(0); tSb.setLength(0)
                    }
                    "is" -> cellHasInline = true
                    "v" -> inV = true
                    "t" -> if (cellHasInline) inInlineT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV) vSb.append(parser.text)
                    if (inInlineT) tSb.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> finishCell()
                    "row" -> {
                        row?.let { r ->
                            while (r.isNotEmpty() && r.last() == null) r.removeAt(r.size - 1)
                            rows.add(r)
                        }
                        row = null
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    fun colIndexFromRef(ref: String): Int {
        if (ref.isEmpty()) return -1
        var col = 0
        for (ch in ref) {
            when (ch) {
                in 'A'..'Z' -> col = col * 26 + (ch - 'A' + 1)
                in 'a'..'z' -> col = col * 26 + (ch - 'a' + 1)
                else -> break
            }
        }
        return col - 1
    }
}
