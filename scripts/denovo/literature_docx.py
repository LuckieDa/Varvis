"""Bounded OOXML text, with source positions rather than rendered page claims.

Never executes Word, macros, fields or external relationships. Unknown visual
content is reported; revisions/fields that could change text are not flattened.
"""
from literature_attachments import members, xml, require, MAX_CELLS

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
NS = {'w': W}
Q = '{' + W + '}'


def read_docx(raw, expansion_budget=None):
    parts = dict(members(raw, expansion_budget))
    require('word/document.xml' in parts and '[Content_Types].xml' in parts,
            'DOCX_DOCUMENT_REQUIRED')
    types = xml(parts['[Content_Types].xml'])
    require(any(n.get('PartName') == '/word/document.xml' and n.get('ContentType') ==
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'
                for n in types), 'DOCX_CONTENT_TYPE_INVALID')
    require(not any('vba' in name.lower() or name.startswith('word/embeddings/')
                    for name in parts), 'DOCX_ACTIVE_CONTENT_UNSUPPORTED')
    units, limitations, consumed = [], [], {'[Content_Types].xml'}

    def emit(part, path, kind, node, **extra):
        texts = []
        paragraphs, pending = [], [node]
        while pending:
            element = pending.pop()
            if element.tag in {Q + 'drawing', Q + 'pict', Q + 'object'}: continue
            if element.tag == Q + 'p': paragraphs.append(element)
            else: pending.extend(reversed(list(element)))
        for p in paragraphs:
            chunks = []
            stack = [p]
            while stack:
                item = stack.pop()
                if item.tag in {Q + 'drawing', Q + 'pict', Q + 'object'}: continue
                if item.tag == Q + 't': chunks.append(item.text or '')
                elif item.tag in {Q + 'br', Q + 'cr'}: chunks.append('\n')
                elif item.tag == Q + 'tab': chunks.append('\t')
                elif item.tag == Q + 'noBreakHyphen': chunks.append('\u2011')
                elif item.tag == Q + 'softHyphen': chunks.append('\u00ad')
                stack.extend(reversed(list(item)))
            texts.append(''.join(chunks))
        text = '\n'.join(texts)
        require(len(text) <= 32768 and len(units) < MAX_CELLS, 'DOCX_TEXT_LIMIT')
        notes = [{'kind': n.tag[len(Q):], 'id': n.get(Q + 'id')} for n in node.iter()
                 if n.tag in {Q + 'footnoteReference', Q + 'endnoteReference'}]
        units.append({'location': part + ':' + path, 'sourcePart': part, 'sourcePath': path,
                      'kind': kind, 'text': text, 'noteReferences': notes, **extra})

    def blocks(parent, part, path):
        counts = {}
        for node in parent:
            local = node.tag.removeprefix(Q)
            counts[local] = counts.get(local, 0) + 1
            here = path + '/' + local + '[' + str(counts[local]) + ']'
            if local == 'p':
                emit(part, here, 'SUPPLEMENT_PARAGRAPH', node)
            elif local == 'tbl':
                require(len(list(node.iter(Q + 'tbl'))) == 1, 'DOCX_NESTED_TABLE_UNSUPPORTED')
                for row_index, row in enumerate(node.findall('w:tr', NS), 1):
                    before = row.find('w:trPr/w:gridBefore', NS)
                    column = int(before.get(Q + 'val', '0')) + 1 if before is not None else 1
                    require(1 <= column <= 512, 'DOCX_TABLE_GRID_INVALID')
                    for cell_index, cell in enumerate(row.findall('w:tc', NS), 1):
                        span = cell.find('w:tcPr/w:gridSpan', NS)
                        width = int(span.get(Q + 'val', '1')) if span is not None else 1
                        merge = cell.find('w:tcPr/w:vMerge', NS)
                        vertical = merge.get(Q + 'val', 'continue') if merge is not None else None
                        require(1 <= width <= 512 and column + width <= 513 and
                                vertical in {None, 'continue', 'restart'}, 'DOCX_TABLE_GRID_INVALID')
                        emit(part, f'{here}/tr[{row_index}]/tc[{cell_index}]', 'SUPPLEMENT_CELL', cell,
                             sheet=part + ':' + here, cell=f'R{row_index}C{column}',
                             sourceCellIndex=cell_index, gridSpan=width, verticalMerge=vertical,
                             header=row.find('w:trPr/w:tblHeader', NS) is not None)
                        column += width
            elif local == 'sdt':
                blocks(node.find('w:sdtContent', NS), part, here + '/sdtContent')
            elif local not in {'sectPr', 'tcPr', 'pPr'}:
                require(False, 'DOCX_BLOCK_UNSUPPORTED')

    for part in ['word/document.xml', 'word/footnotes.xml', 'word/endnotes.xml'] + sorted(
            n for n in parts if n.startswith(('word/header', 'word/footer')) and n.endswith('.xml')):
        if part not in parts: continue
        root = xml(parts[part]); consumed.add(part)
        require(root.tag.startswith(Q), 'DOCX_NAMESPACE_UNSUPPORTED')
        # Never silently choose an accepted/rejected revision or a cached field.
        reject = {Q + t for t in ('ins', 'del', 'moveFrom', 'moveTo', 'altChunk', 'fldSimple', 'fldChar',
                  'instrText', 'dataBinding', 'vanish', 'webHidden', 'sym', 'hMerge')}
        require(not any(n.tag in reject for n in root.iter()),
                'DOCX_TEXT_SEMANTICS_UNSUPPORTED')
        require(all(len(n.findall('w:sdtContent', NS)) == 1 for n in root.iter(Q + 'sdt')),
                'DOCX_TEXT_SEMANTICS_UNSUPPORTED')
        # A whitespace-only placeholder carries no assertion. Continue to
        # reject any placeholder whose display could be mistaken for data.
        placeholders = [n for n in root.iter(Q + 'sdt') if n.find('w:sdtPr/w:showingPlcHdr', NS) is not None]
        require(len(placeholders) == sum(1 for _ in root.iter(Q + 'showingPlcHdr'))
                and all(not ''.join(n.find('w:sdtContent', NS).itertext()).strip() for n in placeholders),
                'DOCX_TEXT_SEMANTICS_UNSUPPORTED')
        require(not any(n.tag.endswith('}AlternateContent') for n in root.iter()),
                'DOCX_ALTERNATE_CONTENT_UNSUPPORTED')
        if any(n.tag in {Q + 'drawing', Q + 'pict', Q + 'object'} for n in root.iter()):
            limitations.append({'sourcePart': part, 'reasonCode': 'PAPER_ATTACHMENT_DOCX_VISUAL_CONTENT_UNREAD'})
        if part == 'word/document.xml':
            body = root.find('w:body', NS)
            require(body is not None, 'DOCX_BODY_REQUIRED')
            blocks(body, part, '/document/body')
        elif part in {'word/footnotes.xml', 'word/endnotes.xml'}:
            kind = 'footnote' if 'footnotes' in part else 'endnote'
            ids = set()
            for note in root:
                identifier = note.get(Q + 'id')
                require(identifier is not None and identifier not in ids, 'DOCX_NOTE_ID_INVALID')
                ids.add(identifier)
                if note.get(Q + 'type') in {'separator', 'continuationSeparator'}: continue
                require(identifier.isdigit(), 'DOCX_NOTE_ID_INVALID')
                blocks(note, part, f'/{kind}s/{kind}[@id={identifier}]')
        else:
            blocks(root, part, '/' + root.tag[len(Q):])
    require(units, 'DOCX_NO_TEXT_UNITS')
    return {'format': 'DOCX', 'status': 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED',
            'units': units, 'limitations': limitations,
            'unparsedParts': [p for p in parts if p not in consumed],
            'completePublicationProven': False,
            'limitation': 'Text and structural positions only; no rendered pages, OCR, comments, '
                          'style-based display inference, clinical ownership or absence inference. '
                          'Merged cells are preserved, not copied into other rows.'}
