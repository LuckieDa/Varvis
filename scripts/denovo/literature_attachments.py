"""Read bounded supplementary tables without executing formulas or guessing roles.

Raw files remain authoritative. Spreadsheet numbers/styles are preserved, not
rendered as clinical values. PDF, images, legacy XLS and nested archives are
explicitly unparsed. No third-party parser, network or workbook modification.
"""
import csv
import io
from pathlib import PurePosixPath
import re
import stat
import struct
import xml.etree.ElementTree as ET
import zipfile

from prepare_literature import sha

MAX_PACKAGE = 50 * 1024 * 1024
MAX_EXPANDED = 100 * 1024 * 1024
MAX_MEMBER = 20 * 1024 * 1024
MAX_FILES = 512
MAX_CELLS = 100000
NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/package/2006/relationships'}


def require(value, reason):
    if not value:
        raise ValueError('PAPER_ATTACHMENT_' + reason)


def members(raw, expansion_budget=None):
    require(0 < len(raw) <= MAX_PACKAGE, 'PACKAGE_SIZE_LIMIT')
    # Bound central-directory allocations BEFORE ZipFile builds ZipInfo objects.
    offset = raw.rfind(b'PK\x05\x06', max(0, len(raw) - 65557))
    require(offset >= 0 and offset + 22 <= len(raw), 'ZIP_DIRECTORY_INVALID')
    disk, start_disk, disk_entries, entries, directory_size, directory_start, comment = struct.unpack_from('<4H2IH', raw, offset + 4)
    require(disk == start_disk == 0 and disk_entries == entries and entries <= MAX_FILES
            and offset + 22 + comment == len(raw), 'ZIP_DIRECTORY_LIMIT')
    require(b'PK\x06\x07' not in raw[max(0, offset - 20):offset], 'ZIP64_UNSUPPORTED')
    require(directory_start + directory_size == offset, 'ZIP_DIRECTORY_INVALID')
    cursor, actual = directory_start, 0
    while cursor < offset:
        require(cursor + 46 <= offset and raw[cursor:cursor + 4] == b'PK\x01\x02', 'ZIP_DIRECTORY_INVALID')
        name_size, extra_size, comment_size = struct.unpack_from('<3H', raw, cursor + 28)
        source_name = raw[cursor + 46:cursor + 46 + name_size]
        # Windows ZipInfo normalizes backslashes and truncates NUL before exposing
        # filename; reject the original central-directory bytes first.
        require(source_name and b'\\' not in source_name and b':' not in source_name
                and not source_name.startswith(b'/') and b'..' not in source_name.split(b'/')
                and not any(value < 32 for value in source_name), 'ZIP_PATH_INVALID')
        cursor += 46 + name_size + extra_size + comment_size
        actual += 1
        require(actual <= MAX_FILES, 'ZIP_DIRECTORY_LIMIT')
    require(cursor == offset and actual == entries, 'ZIP_DIRECTORY_INVALID')
    result, seen, total = [], set(), 0
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.infolist()) == entries, 'ZIP_DIRECTORY_INVALID')
        for item in archive.infolist():
            name = item.filename
            path = PurePosixPath(name)
            require(name and '\\' not in name and ':' not in name and not path.is_absolute()
                    and '..' not in path.parts and not any(ord(c) < 32 for c in name), 'ZIP_PATH_INVALID')
            key = name.rstrip('/').casefold()
            require(key not in seen, 'ZIP_DUPLICATE_NAME')
            seen.add(key)
            require(not stat.S_ISLNK(item.external_attr >> 16) and not item.flag_bits & 1, 'ZIP_LINK_OR_ENCRYPTION')
            require(item.file_size <= MAX_MEMBER and item.file_size <= max(1, item.compress_size) * 300,
                    'ZIP_MEMBER_LIMIT')
            total += item.file_size
            require(total <= MAX_EXPANDED and (expansion_budget is None or total <= expansion_budget[0]),
                    'ZIP_EXPANDED_LIMIT')
        if expansion_budget is not None:
            # Reserve before reads, including corrupt members; failure cannot reset
            # the batch budget and repeat expensive expansion in later XLSX files.
            expansion_budget[0] -= total
        for item in archive.infolist():
            if item.is_dir(): continue
            with archive.open(item) as stream:
                body = stream.read(MAX_MEMBER + 1)  # ZipFile checks CRC while reaching EOF.
            require(len(body) == item.file_size, 'ZIP_MEMBER_SIZE_MISMATCH')
            result.append((item.filename, body))
    return result


def xml(raw):
    require(len(raw) <= MAX_MEMBER, 'XML_SIZE_LIMIT')
    text = raw.decode('utf-8-sig')
    require(not re.search(r'<!\s*(?:DOCTYPE|ENTITY)', text, re.I), 'XML_ENTITY_FORBIDDEN')
    root = ET.fromstring(text)
    stack, count = [(root, 0)], 0
    while stack:
        element, depth = stack.pop()
        count += 1
        require(count <= 250000 and depth <= 64, 'XML_STRUCTURE_LIMIT')
        stack.extend((child, depth + 1) for child in element)
    return root


def txt(element):
    return '' if element is None else ''.join(element.itertext())


def workbook(raw, expansion_budget=None):
    parts = dict(members(raw, expansion_budget))
    require('xl/workbook.xml' in parts and 'xl/_rels/workbook.xml.rels' in parts, 'XLSX_WORKBOOK_REQUIRED')
    require(not any('vbaProject' in n for n in parts), 'XLSX_MACRO_UNSUPPORTED')
    book = xml(parts['xl/workbook.xml'])
    rels = xml(parts['xl/_rels/workbook.xml.rels'])
    relationships = {}
    for relation in rels:
        key = relation.get('Id')
        require(key and key not in relationships, 'XLSX_RELATION_INVALID')
        relationships[key] = relation
    strings = []
    if 'xl/sharedStrings.xml' in parts:
        strings = [''.join(n.text or '' for n in si.findall('.//s:t', NS))
                   for si in xml(parts['xl/sharedStrings.xml']).findall('s:si', NS)]
        require(len(strings) <= MAX_CELLS, 'CELL_LIMIT')
    # Record source formatting; never translate date serials or repair gene symbols.
    styles = None
    if 'xl/styles.xml' in parts:
        style_root = xml(parts['xl/styles.xml'])
        styles = {'numberFormats': [dict(n.attrib) for n in style_root.findall('s:numFmts/s:numFmt', NS)],
                  'cellFormats': [dict(n.attrib) for n in style_root.findall('s:cellXfs/s:xf', NS)]}
    sheets, count, seen_sheets = [], 0, set()
    for sheet in book.findall('s:sheets/s:sheet', NS):
        name = sheet.get('name')
        require(name and name not in seen_sheets and len(sheets) < 64, 'SHEET_IDENTITY_INVALID')
        seen_sheets.add(name)
        relation = relationships.get(sheet.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'))
        require(relation is not None and relation.get('TargetMode', 'Internal') == 'Internal'
                and (relation.get('Type') or '').endswith('/worksheet'), 'EXTERNAL_OR_UNSUPPORTED_SHEET')
        target = relation.get('Target', '')
        require(target and '..' not in PurePosixPath(target).parts and '\\' not in target
                and ':' not in target, 'XLSX_RELATION_INVALID')
        path = target.lstrip('/') if target.startswith('/') else 'xl/' + target
        require(path in parts and path.startswith('xl/'), 'XLSX_SHEET_MISSING')
        root = xml(parts[path])
        cells, addresses = [], set()
        for cell in root.findall('s:sheetData/s:row/s:c', NS):
            address = cell.get('r', '')
            require(re.fullmatch(r'[A-Z]{1,3}[1-9][0-9]{0,6}', address) and address not in addresses, 'CELL_ADDRESS_INVALID')
            addresses.add(address)
            count += 1
            require(count <= MAX_CELLS, 'CELL_LIMIT')
            kind, value = cell.get('t', 'n'), cell.findtext('s:v', None, NS)
            formula = cell.find('s:f', NS)
            if kind == 's':
                require(value is not None and value.isdigit() and int(value) < len(strings), 'SHARED_STRING_INVALID')
                text = strings[int(value)]
            elif kind == 'inlineStr':
                text = ''.join(n.text or '' for n in cell.findall('s:is//s:t', NS))
            else:
                text = value
            require(text is None or len(text) <= 32768, 'CELL_TEXT_LIMIT')
            cells.append({'address': address, 'storedType': kind, 'rawValue': value, 'text': text,
                          'styleIndex': cell.get('s'), 'formula': None if formula is None else txt(formula),
                          'formulaAttributes': None if formula is None else dict(formula.attrib),
                          'use': 'FORMULA_NOT_EVALUATED' if formula is not None else 'TEXT'
                                 if kind in {'s', 'inlineStr', 'str'} else 'RAW_TYPED_VALUE_NOT_INTERPRETED'})
        merged_ranges = [n.get('ref') for n in root.findall('s:mergeCells/s:mergeCell', NS)]
        def coordinate(address):
            match = re.fullmatch(r'([A-Z]{1,3})([1-9][0-9]{0,6})', address or '')
            require(match is not None, 'MERGED_RANGE_INVALID')
            column = 0
            for letter in match[1]: column = column * 26 + ord(letter) - 64
            return int(match[2]), column
        ranges = []
        for ref in merged_ranges:
            parts_ref = (ref or '').split(':')
            require(len(parts_ref) == 2, 'MERGED_RANGE_INVALID')
            start, end = map(coordinate, parts_ref)
            require(start[0] <= end[0] and start[1] <= end[1], 'MERGED_RANGE_INVALID')
            ranges.append((start, end))
        require(len(ranges) * len(cells) <= 2000000, 'MERGED_RANGE_WORK_LIMIT')
        for cell in cells:
            row, column = coordinate(cell['address'])
            if any(a[0] <= row <= b[0] and a[1] <= column <= b[1] for a, b in ranges):
                cell['mergedCell'] = True
        sheets.append({'name': name, 'state': sheet.get('state', 'visible'), 'sourcePart': path,
                       'cells': cells, 'mergedRanges': merged_ranges,
                       'rowAttributes': [dict(n.attrib) for n in root.findall('s:sheetData/s:row', NS)],
                       'columnAttributes': [dict(n.attrib) for n in root.findall('s:cols/s:col', NS)]})
    require(sheets, 'XLSX_NO_SHEETS')
    return {'format': 'XLSX', 'status': 'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED', 'sheets': sheets,
            'styles': styles, 'workbookProperties': [dict(n.attrib) for n in book.findall('s:workbookPr', NS)],
            'unparsedParts': [n for n in parts if not n.endswith('.rels') and n not in {
                '[Content_Types].xml', 'xl/workbook.xml', 'xl/styles.xml', 'xl/sharedStrings.xml',
                *(s['sourcePart'] for s in sheets)}],
            'limitation': 'Only worksheet cells and layout metadata read. No formula evaluation, drawings, comments or clinical column inference.'}


def delimited(raw, delimiter):
    encoding = 'utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig'
    text = raw.decode(encoding)
    rows, total = [], 0
    for number, row in enumerate(csv.reader(io.StringIO(text, newline=''), delimiter=delimiter, strict=True), 1):
        total += len(row)
        require(number <= 10000 and len(row) <= 512 and total <= MAX_CELLS
                and all(len(cell) <= 32768 for cell in row), 'CELL_LIMIT')
        rows.append({'row': number, 'cells': [{'column': col, 'text': value} for col, value in enumerate(row, 1)]})
    return {'format': 'TSV' if delimiter == '\t' else 'CSV', 'status': 'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED',
            'encoding': encoding, 'rows': rows, 'limitation': 'All values retained as text; no inferred header, role or formula execution.'}


def parse_attachment(name, raw, expansion_budget=None, *, allow_pdf=False, allow_archive=False):
    suffix = PurePosixPath(name).suffix.lower()
    result = {'originalName': name, 'sha256': sha(raw), 'bytes': len(raw), 'format': suffix.lstrip('.').upper(),
              'businessPublishable': False}
    try:
        require(len(raw) <= MAX_MEMBER, 'MEMBER_LIMIT')
        if suffix == '.xlsx':
            parsed = workbook(raw, expansion_budget)
        elif suffix == '.docx':
            from literature_docx import read_docx
            parsed = read_docx(raw, expansion_budget)
        elif suffix in {'.csv', '.tsv'}:
            parsed = delimited(raw, '\t' if suffix == '.tsv' else ',')
        elif suffix == '.pdf' and allow_pdf:
            from literature_pdf import read_pdf
            parsed = read_pdf(raw, expansion_budget)
        elif suffix == '.zip' and allow_archive:
            # One official envelope only. Reuse the same expansion budget for
            # its members and the Office packages inside; never extract files.
            budget = expansion_budget if expansion_budget is not None else [MAX_EXPANDED]
            children = []
            count = 0
            for member_name, member_raw in members(raw, budget):
                child = parse_attachment(member_name, member_raw, budget, allow_pdf=allow_pdf)
                count += sum(len(s['cells']) for s in child.get('sheets', []))
                count += sum(len(r['cells']) for r in child.get('rows', [])) + len(child.get('units', []))
                require(count <= MAX_CELLS, 'ARCHIVE_UNIT_LIMIT')
                children.append(child)
            parsed = {'format': 'ZIP', 'status': 'ARCHIVE_MEMBERS_READ', 'members': children,
                      'limitation': 'One archive envelope; member bytes pinned separately. Unsupported or nested archives remain unparsed. No clinical interpretation.'}
        else:
            return {**result, 'status': 'ARCHIVED_NOT_PARSED', 'reasonCode': 'PAPER_ATTACHMENT_FORMAT_UNSUPPORTED'}
        return {**result, **parsed}
    except (ValueError, KeyError, IndexError, UnicodeError, ET.ParseError, zipfile.BadZipFile,
            RuntimeError, NotImplementedError, csv.Error, OSError) as error:
        code = str(error)
        return {**result, 'status': 'ARCHIVED_NOT_PARSED',
                'reasonCode': code if code.startswith('PAPER_ATTACHMENT_') else 'PAPER_ATTACHMENT_PARSE_FAILED'}
