"""Read existing pinned GENCODE and accepted reference resources for preparation.

One bounded streaming inventory pass per instance, reusable across targets. The
indexed coding subset is NOT a complete biological/functional transcript set.
"""
import hashlib
from pathlib import Path

from assemble_materials import child, pinned, parse, sha, require
from normalize_public_panel import BoundedReference


def local(root, name):
    path = child(root, name).resolve(strict=True)
    require(path.is_relative_to(Path(root).resolve() / 'local') and path.is_file(), 'START_LOSS_LOCAL_RESOURCE_REQUIRED')
    return path


def read_pin(root, spec, maximum=1_048_576):
    return pinned(local(root, spec['path']), spec['sha256'], maximum)


def stamp(path):
    s = path.stat()
    return s.st_size, s.st_mtime_ns, s.st_dev, s.st_ino


class NativeSources:
    def __init__(self, root, config, genes):
        self.root, self.config = Path(root).resolve(strict=True), config
        require(1 <= len(genes) <= 100, 'START_LOSS_GENE_LIMIT')
        self.manifest_raw = read_pin(root, config['gencodeManifest'])
        manifest = self.manifest = parse(self.manifest_raw)
        require(manifest['schemaVersion'] == 'gencode-transcript-index-v1' and manifest['assembly'] == 'GRCh38',
                'START_LOSS_GENCODE_IDENTITY_INVALID')
        index_raw = read_pin(root, config['gencodeIndex'], 32_000_000)
        require(sha(index_raw) == manifest['index']['sha256'], 'START_LOSS_INDEX_MANIFEST_MISMATCH')
        index = {}
        for line in index_raw.decode('utf-8').splitlines():
            tx, offset, size, digest = line.split('\t')
            require(tx not in index and offset.isdigit() and size.isdigit() and 0 < int(size) <= 1_000_000,
                    'START_LOSS_INDEX_INVALID')
            index[tx] = int(offset), int(size), digest
        require(len(index) <= 200_000, 'START_LOSS_INDEX_LIMIT')
        path = local(root, config['gencodeData']); before = stamp(path)
        require(before[0] == manifest['data']['bytes'] and before[0] <= 512_000_000, 'START_LOSS_DATA_SIZE_INVALID')
        self.rows = {gene: [] for gene in genes}
        hasher, offset, count = hashlib.sha256(), 0, 0
        seen = set()
        with path.open('rb') as stream:
            while raw := stream.readline(1_000_001):
                require(len(raw) <= 1_000_000 and raw.endswith(b'\n'), 'START_LOSS_ROW_LIMIT')
                hasher.update(raw); row = parse(raw); tx = row['transcript']
                require(tx not in seen and index.get(tx) == (offset, len(raw), sha(raw)), 'START_LOSS_ROW_INDEX_MISMATCH')
                seen.add(tx)
                if row['hgncId'] in self.rows:
                    require(len(self.rows[row['hgncId']]) < 1000, 'START_LOSS_GENE_TRANSCRIPT_LIMIT')
                    self.rows[row['hgncId']].append(row)
                offset += len(raw); count += 1
        require(before == stamp(path) and hasher.hexdigest() == manifest['data']['sha256']
                and count == len(index), 'START_LOSS_DATA_CHANGED_OR_PIN_MISMATCH')
        self.data_path, self.data_stamp = path, before
        self.identity = {'manifestSha256': sha(self.manifest_raw), 'dataSha256': hasher.hexdigest(),
                         'indexSha256': sha(index_raw), 'scannedRecords': count, 'inventoryPasses': 1,
                         'coverage': 'INDEXED_CODING_TRANSCRIPTS_ONLY', 'functionalCoverageComplete': False,
                         'excludedCounts': manifest['counts'], 'source': manifest['source']}
        self.reference = None

    def inventory(self, gene):
        require(stamp(self.data_path) == self.data_stamp, 'START_LOSS_DATA_CHANGED')
        require(gene in self.rows, 'START_LOSS_GENE_NOT_REQUESTED')
        return {'identity': self.identity, 'hgncId': gene, 'transcripts': self.rows[gene]}

    def model(self, gene, transcript):
        rows = [r for r in self.inventory(gene)['transcripts'] if r['transcript'] == transcript]
        require(len(rows) == 1, 'START_LOSS_EXACT_TRANSCRIPT_UNAVAILABLE')
        return rows[0]

    def region(self, contig, start, end):
        if self.reference is None:
            c = self.config['reference']; raw = read_pin(self.root, c['receipt']); envelope = parse(raw)
            resource_root = child(self.root, c['resourceRoot']).resolve(strict=True)
            require(resource_root.is_relative_to(self.root / 'local'), 'START_LOSS_REFERENCE_OUTSIDE_LOCAL')
            self.reference = BoundedReference(resource_root, local(self.root, c['receipt']['path']),
                c['receipt']['sha256'], envelope['source'], envelope['version'], 'reference', c['sha256'],
                local(self.root, c['aliases']['path']), c['aliases']['sha256'], {},
                {'cumulativeLeftExtensions': [0], 'initialRightPaddingBases': 0,
                 'maximumWindowBases': 1_000_000, 'maximumWindowReads': 1000, 'maximumTotalFastaBases': 1_000_000})
        key = f'{contig}:{start}-{end}'
        self.reference.windows[key] = {'contig': contig, 'start': start, 'end': end}
        return self.reference.read(key, 0, end)[0]

    def reference_proof(self):
        if self.reference is None:
            return {'status': 'NOT_READ'}
        self.reference.recheck()
        return {'status': 'READ_FROM_ACCEPTED_REFERENCE', 'binding': self.config['reference'],
                'reads': self.reference.reads, 'bases': self.reference.returned_bases,
                'observations': self.reference.observations}
