package com.kxin.classtable.data.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 轻量 xlsx 解析:只读第一个工作表(sheet1.xml)+ 共享字符串表,零第三方依赖。
 * 返回二维行列表(缺失单元格以空串补齐,便于按列序解析)。
 */
object XlsxParser {

    fun parse(bytes: ByteArray): List<List<String>> {
        val shared = mutableListOf<String>()
        var sheet: List<List<String>>? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "xl/sharedStrings.xml" -> parseSharedStrings(zip, shared)
                    "xl/worksheets/sheet1.xml" -> sheet = parseSheet(zip, shared)
                }
                zip.closeEntry()
            }
        }
        return sheet ?: emptyList()
    }

    /** <si> 可能直接含 <t>,也可能含多个 <r><t> 富文本段,全部拼接。 */
    private fun parseSharedStrings(input: java.io.InputStream, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var inSi = false
        var inT = false
        val sb = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inSi = true
                        sb.setLength(0)
                    }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> {
                        out.add(sb.toString())
                        inSi = false
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseSheet(input: java.io.InputStream, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var currentRow: MutableList<String>? = null
        var colIndex = -1
        var cellType: String? = null
        var inV = false
        var inIsT = false
        val cellText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        colIndex = refToCol(parser.getAttributeValue(null, "r") ?: "")
                        cellType = parser.getAttributeValue(null, "t")
                        cellText.setLength(0)
                    }
                    "v" -> if (cellType != "inlineStr") inV = true
                    "t" -> inIsT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV) cellText.append(parser.text)
                    else if (inIsT) cellText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "t" -> inIsT = false
                    "c" -> {
                        val row = currentRow
                        if (row != null && colIndex >= 0) {
                            val value = when (cellType) {
                                "s" -> {
                                    val idx = cellText.toString().trim().toIntOrNull() ?: -1
                                    shared.getOrNull(idx) ?: ""
                                }
                                else -> cellText.toString().trim()
                            }
                            while (row.size <= colIndex) row.add("")
                            row[colIndex] = value
                        }
                        cellType = null
                    }
                    "row" -> {
                        currentRow?.let { rows.add(it) }
                        currentRow = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** "C5" → 2(0-based 列索引)。 */
    private fun refToCol(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1) else break
        }
        return col - 1
    }
}
