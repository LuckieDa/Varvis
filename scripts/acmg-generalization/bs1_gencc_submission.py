"""Replay one GenCC submission HTML original; never infer a specific X MOI.

The projection is deterministic and retains byte locators into the pinned HTML.
It is not a substituted JSON original, a scientific adoption, or a diagnosis.
"""
from html.parser import HTMLParser
import hashlib
import re
from urllib.parse import urlsplit

VERSION = 'gencc-submission-html-v1'
MOI = {
    'HP:0000006': ('AUTOSOMAL_DOMINANT', {'Autosomal dominant', 'Autosomal dominant inheritance'}),
    'HP:0000007': ('AUTOSOMAL_RECESSIVE', {'Autosomal recessive', 'Autosomal recessive inheritance'}),
    'HP:0001419': ('X_LINKED_RECESSIVE', {'X-linked recessive', 'X-linked recessive inheritance'}),
    'HP:0001423': ('X_LINKED_DOMINANT', {'X-linked dominant', 'X-linked dominant inheritance'}),
    'HP:0001450': ('Y_LINKED', {'Y-linked', 'Y-linked inheritance'}),
    'HP:0001417': ('HP:0001417', {'X-linked', 'X-linked inheritance'}),
}
CLASSIFICATIONS = {'GENCC:100001': 'Definitive', 'GENCC:100002': 'Strong',
                   'GENCC:100003': 'Moderate', 'GENCC:100004': 'Limited',
                   'GENCC:100005': 'Disputed', 'GENCC:100006': 'Refuted'}
VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link',
        'meta', 'param', 'source', 'track', 'wbr'}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


class _Node:
    def __init__(self, tag, attrs, start, parent=None):
        self.tag, self.attrs, self.start = tag, dict(attrs), start
        self.end, self.parent, self.children = start, parent, []

    def text(self):
        return ' '.join(' '.join(c.text() if isinstance(c, _Node) else c
                                 for c in self.children).split())

    def nodes(self):
        for c in self.children:
            if isinstance(c, _Node):
                yield c
                yield from c.nodes()


class _Document(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.raw = text
        self.lines = [0]
        self.lines.extend(m.end() for m in re.finditer('\n', text))
        self.root = _Node('document', [], 0)
        self.stack = [self.root]
        self.feed(text)
        self.close()
        require(len(self.stack) == 1, 'GENCC_HTML_UNCLOSED_ELEMENT')

    def position(self):
        line, col = self.getpos()
        return self.lines[line - 1] + col

    def handle_starttag(self, tag, attrs):
        require(len(attrs) == len({k for k, _ in attrs}), 'GENCC_HTML_DUPLICATE_ATTRIBUTE')
        node = _Node(tag, attrs, self.position(), self.stack[-1])
        self.stack[-1].children.append(node)
        if tag not in VOID:
            self.stack.append(node)
        else:
            node.end = self.position() + len(self.get_starttag_text())

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.stack[-1].end = self.position() + len(self.get_starttag_text())
            self.stack.pop()

    def handle_endtag(self, tag):
        require(len(self.stack) > 1 and self.stack[-1].tag == tag,
                'GENCC_HTML_INVALID_NESTING')
        self.stack[-1].end = self.raw.find('>', self.position()) + 1
        self.stack.pop()

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def parse_submission(raw, source_url):
    """Return one exact assertion projection, using the HTML's explicit HPO term."""
    url = urlsplit(source_url)
    accession = re.fullmatch(r'/submissions/(SGC-[1-9][0-9]*\.[1-9][0-9]*)', url.path)
    require(url.scheme == 'https' and url.hostname in {'thegencc.org', 'www.thegencc.org'}
            and accession and not url.query and not url.fragment and not url.username,
            'GENCC_SUBMISSION_URL_INVALID')
    require(isinstance(raw, bytes) and len(raw) <= 5 * 1024 * 1024, 'GENCC_HTML_SIZE_INVALID')
    try:
        text = raw.decode('utf-8')
    except UnicodeError:
        raise ValueError('GENCC_HTML_UTF8_INVALID') from None
    doc = _Document(text)
    all_nodes = list(doc.root.nodes())
    bindings = {}

    def field(label):
        labels = [n for n in all_nodes if n.tag == 'div'
                  and 'col-span-2' in n.attrs.get('class', '').split() and n.text() == label + ':']
        require(len(labels) == 1, 'GENCC_SUBMISSION_FIELD_AMBIGUOUS:' + label)
        node = labels[0]
        siblings = [n for n in node.parent.children if isinstance(n, _Node)]
        index = siblings.index(node)
        require(index + 1 < len(siblings) and siblings[index + 1].tag == 'div',
                'GENCC_SUBMISSION_FIELD_MISSING:' + label)
        value = siblings[index + 1]
        require(value.text(), 'GENCC_SUBMISSION_FIELD_MISSING:' + label)
        start, end = len(text[:node.start].encode('utf-8')), len(text[:value.end].encode('utf-8'))
        fragment = raw[start:end]
        bindings[label] = {'locator': 'utf8-bytes:' + str(start) + '-' + str(end),
                           'contentSha256': hashlib.sha256(fragment).hexdigest(), 'text': value.text()}
        return value

    def curie(node, pattern):
        values = set(re.findall(pattern, node.text()))
        require(len(values) == 1, 'GENCC_SUBMISSION_ID_AMBIGUOUS')
        return values.pop()

    submitter = field('Submitter').text()
    actual_accession = field('Accession').text()
    require(actual_accession == accession[1], 'GENCC_SUBMISSION_ACCESSION_MISMATCH')
    classification_node = field('Classification')
    classification_id = curie(classification_node, r'GENCC:[0-9]{6}')
    classification = CLASSIFICATIONS.get(classification_id)
    require(classification and classification_node.text() == classification + ' ' + classification_id,
            'GENCC_SUBMISSION_CLASSIFICATION_UNSUPPORTED')
    gene = field('Gene')
    gene_id = curie(gene, r'HGNC:[1-9][0-9]*')
    symbols = [n.text() for n in gene.nodes() if n.tag == 'a'
               and n.attrs.get('href') in {'https://thegencc.org/genes/' + gene_id,
                                           'https://www.thegencc.org/genes/' + gene_id}]
    require(len(symbols) == 1 and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', symbols[0]),
            'GENCC_SUBMISSION_GENE_AMBIGUOUS')
    disease = field('Disease')
    disease_id = curie(disease, r'MONDO:[0-9]{7}')
    titles = [n.text() for n in disease.nodes() if n.tag == 'div'
              and 'font-normal' in n.attrs.get('class', '').split()]
    require(titles and titles[0], 'GENCC_SUBMISSION_DISEASE_TITLE_MISSING')
    mode = field('Mode Of Inheritance')
    mode_id = curie(mode, r'HP:[0-9]{7}')
    mode_spec = MOI.get(mode_id)
    require(mode_spec, 'GENCC_SUBMISSION_MOI_UNSUPPORTED')
    mode_term = mode.text().removesuffix(' ' + mode_id)
    require(mode_term in mode_spec[1], 'GENCC_SUBMISSION_MOI_LABEL_CONFLICT')
    evaluated, submitted = field('Evaluated Date').text(), field('Submitted Date').text()
    for date in (evaluated, submitted):
        require(re.fullmatch(r'[0-9]{2}/[0-9]{2}/[0-9]{4}', date), 'GENCC_SUBMISSION_DATE_INVALID')
    return [{'hgncId': gene_id, 'symbol': symbols[0], 'mondoId': disease_id,
             'diseaseLabel': titles[0], 'moi': mode_spec[0], 'classification': classification,
             'classificationDate': evaluated, 'reportLocator': source_url,
             'original': {'parserVersion': VERSION, 'accession': actual_accession,
                          'submitter': submitter, 'submittedDate': submitted,
                          'moiCurie': mode_id, 'moiTerm': mode_term,
                          'classificationCurie': classification_id,
                          'rawSha256': hashlib.sha256(raw).hexdigest(), 'fieldBindings': bindings}}]
