"""Isolated parser entry point. No external actions, OCR or active PDF execution."""
import io
import json
from pathlib import Path
import re
import sys


def parse(raw):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'local/tools/literature-pdf/lib'))
    import pdfplumber
    if not raw.startswith(b'%PDF-'): raise ValueError('PAPER_ATTACHMENT_PDF_SIGNATURE_INVALID')
    units, sheets, limitations = [], [], []
    total, cell_count, derived_characters = 0, 0, 0
    with pdfplumber.open(io.BytesIO(raw), strict_metadata=True) as pdf:
        if pdf.doc.encryption: raise ValueError('PAPER_ATTACHMENT_PDF_ENCRYPTED')
        if not 0 < len(pdf.pages) <= 100: raise ValueError('PAPER_ATTACHMENT_PDF_PAGE_LIMIT')
        for number, page in enumerate(pdf.pages, 1):
            if len(page.chars) > 100000: raise ValueError('PAPER_ATTACHMENT_PDF_CHARACTER_LIMIT')
            text = page.extract_text() or ''
            total += len(text)
            if total > 1000000: raise ValueError('PAPER_ATTACHMENT_PDF_CHARACTER_LIMIT')
            if not text.strip(): limitations.append({'page': number, 'reasonCode': 'PDF_PAGE_NO_EXTRACTABLE_TEXT'})
            if page.images: limitations.append({'page': number, 'reasonCode': 'PDF_IMAGES_NOT_INTERPRETED'})
            for line_number, line in enumerate(page.extract_text_lines(return_chars=False), 1):
                if len(units) >= 20000 or len(line['text']) > 32768:
                    raise ValueError('PAPER_ATTACHMENT_PDF_TEXT_UNIT_LIMIT')
                derived_characters += len(line['text'])
                units.append({'location': f'page:{number}:line:{line_number}', 'kind': 'SUPPLEMENT_PARAGRAPH',
                              'text': line['text'], 'page': number, 'sourcePart': 'pdf',
                              'sourcePath': f'/page[{number}]/line[{line_number}]',
                              'bbox': [line[k] for k in ('x0', 'top', 'x1', 'bottom')],
                              'pdfTextOnly': True})
            # Shaded row backgrounds are not ruled cell borders. Retain thin
            # filled rules and stroked rectangles; never infer grids from colour.
            grid_page = page.filter(lambda obj: obj.get('object_type') != 'rect' or obj.get('stroke') or
                                    obj.get('width', 0) <= 2 or obj.get('height', 0) <= 2)
            tables = grid_page.find_tables({'vertical_strategy': 'lines', 'horizontal_strategy': 'lines'})
            for index, table in enumerate(tables, 1):
                values = table.extract()
                if not values or max(map(len, values)) > 128: continue
                cells = []
                for row_index, (row, contents) in enumerate(zip(table.rows, values), 1):
                    for col, (box, value) in enumerate(zip(row.cells, contents), 1):
                        cell_count += 1
                        if cell_count > 20000: raise ValueError('PAPER_ATTACHMENT_PDF_CELL_LIMIT')
                        derived_characters += len(value or '')
                        if len(value or '') > 32768 or derived_characters > 2000000:
                            raise ValueError('PAPER_ATTACHMENT_PDF_CHARACTER_LIMIT')
                        cells.append({'address': f'R{row_index}C{col}', 'text': value,
                                      'use': 'TEXT' if box is not None else 'PDF_MERGED_OR_MISSING_CELL',
                                      'bbox': box, 'page': number,
                                      'sourcePath': f'/page[{number}]/table[{index}]/row[{row_index}]/cell[{col}]'})
                sheets.append({'name': f'page:{number}:table:{index}', 'cells': cells,
                               'page': number, 'bbox': table.bbox,
                               'headerContinuation': 'NOT_INFERRED'})
            page.close()
    return {'format': 'PDF', 'status': 'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED',
            'sheets': sheets, 'units': units, 'limitations': limitations,
            'limitation': 'Native text and ruled grid only. No OCR, image interpretation, inferred column roles, '
                          'cross-page header inheritance, merged-cell fill or clinical adoption.'}


if __name__ == '__main__':
    try:
        raw = sys.stdin.buffer.read(20 * 1024 * 1024 + 1)
        if len(raw) > 20 * 1024 * 1024: raise ValueError('PAPER_ATTACHMENT_MEMBER_LIMIT')
        result = parse(raw)
        output = json.dumps(result, ensure_ascii=True, separators=(',', ':')).encode()
        if len(output) > 16 * 1024 * 1024: raise ValueError('PAPER_ATTACHMENT_PDF_OUTPUT_LIMIT')
        sys.stdout.buffer.write(output)
    except Exception as error:
        code = str(error)
        if not re.fullmatch(r'PAPER_ATTACHMENT_[A-Z_]+', code): code = 'PAPER_ATTACHMENT_PDF_PARSE_FAILED'
        sys.stdout.write(json.dumps({'error': code})); sys.exit(1)
