"""文档解析器，支持 PDF、Word、PPT、Excel、TXT 格式的文本提取。

T1 起新增「带位置信息」的解析：
- PDF 逐页返回 ``(page_no, text)``——原来是把全文拼成一个 str，页码物理上取不到；
- docx 按 body 顺序取段落与表格——原来只取 ``doc.paragraphs``，丢掉所有表格，
  而招标文件里"投标人须知前附表"恰好全是表格。

旧的 ``extract_text_from_file`` 保留不变（eval 脚本与历史路径仍用它），
新代码走 ``extract_blocks``。
"""
import fitz
from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph
from pptx import Presentation
from openpyxl import load_workbook


def extract_text_from_file(file_path: str, file_type: str) -> str:
    extractors = {
        "pdf": extract_pdf_text,
        "docx": extract_docx_text,
        "pptx": extract_pptx_text,
        "xlsx": extract_xlsx_text,
        "txt": extract_txt_text
    }
    if file_type not in extractors:
        raise ValueError("不支持的文件类型")
    return extractors[file_type](file_path)


# 带位置信息的解析（T1）

def extract_blocks(file_path: str, file_type: str):
    """返回 ``[{"text": str, "page": int|None, "is_table": bool}]``。

    - PDF：每个物理页一个 block，``page`` 从 1 开始；
    - docx：每个段落 / 表格一个 block，顺序与 body 一致，``page`` 为 None（docx 无页码）；
    - pptx/xlsx/txt：整体一个 block（或按 sheet/slide 分），``page`` 为 None。
    """
    if file_type == "pdf":
        return [{"text": t, "page": p, "is_table": False}
                for p, t in extract_pdf_pages(file_path) if t.strip()]
    if file_type == "docx":
        return [{"text": t, "page": None, "is_table": is_t}
                for t, is_t in extract_docx_blocks(file_path) if t.strip()]
    if file_type == "pptx":
        return [{"text": extract_pptx_text(file_path), "page": None, "is_table": False}]
    if file_type == "xlsx":
        out = []
        for sheet_name, text in extract_xlsx_sheets(file_path):
            if text.strip():
                out.append({"text": text, "page": None, "is_table": True,
                            "section_hint": sheet_name})
        return out
    if file_type == "txt":
        return [{"text": extract_txt_text(file_path), "page": None, "is_table": False}]
    raise ValueError("不支持的文件类型")


def extract_pdf_pages(pdf_path):
    """逐页返回 ``[(page_no, text)]``，page_no 从 1 开始。"""
    pages = []
    with fitz.open(pdf_path) as doc:
        for i, page in enumerate(doc, 1):
            pages.append((i, page.get_text()))
    return pages


def extract_docx_blocks(docx_path):
    """按 body 顺序返回 ``[(text, is_table)]``，段落与表格都不漏。"""
    doc = Document(docx_path)
    blocks = []
    for child in doc.element.body.iterchildren():
        if child.tag == qn("w:p"):
            para = Paragraph(child, doc)
            if para.text.strip():
                blocks.append((para.text, False))
        elif child.tag == qn("w:tbl"):
            table = Table(child, doc)
            rows = []
            for row in table.rows:
                cells = [c.text.strip() for c in row.cells]
                if any(cells):
                    rows.append(" | ".join(cells))
            if rows:
                blocks.append(("\n".join(rows), True))
    return blocks


# 旧版整段解析（保留兼容）

def extract_pdf_text(pdf_path):
    text = ""
    with fitz.open(pdf_path) as doc:
        for page in doc:
            text += page.get_text()
    return text


def extract_docx_text(docx_path):
    doc = Document(docx_path)
    return "\n".join([p.text for p in doc.paragraphs])


def extract_pptx_text(pptx_path):
    prs = Presentation(pptx_path)
    text = ""
    for slide in prs.slides:
        for shape in slide.shapes:
            if hasattr(shape, "text"):
                text += shape.text + "\n"
    return text


def extract_xlsx_text(xlsx_path):
    return "\n".join(t for _, t in extract_xlsx_sheets(xlsx_path))


def extract_xlsx_sheets(xlsx_path):
    """按 sheet 返回 ``[(sheet_name, text)]``。"""
    wb = load_workbook(xlsx_path, read_only=True)
    try:
        out = []
        for sheet in wb:
            text = ""
            for row in sheet.iter_rows(values_only=True):
                text += " ".join([str(c) for c in row if c]) + "\n"
            out.append((sheet.title, text))
        return out
    finally:
        wb.close()


def extract_txt_text(txt_path):
    encodings = ["utf-8", "gbk", "gb2312", "latin-1"]
    for enc in encodings:
        try:
            with open(txt_path, "r", encoding=enc) as f:
                return f.read()
        except (UnicodeDecodeError, LookupError):
            continue
    raise ValueError(f"无法识别文件编码：{txt_path}")
