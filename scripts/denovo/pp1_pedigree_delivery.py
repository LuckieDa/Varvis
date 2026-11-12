"""Freeze verified figure originals with a paper task; replay only captured bytes."""
from pathlib import Path
from model_literature_contract import digest,parse
from model_transport import bounded
from pp1_literature_contract import require


def freeze(library, source, qualification, output):
    wanted={x['sourceRef']['visualPairSha256'] for x in qualification.get('memberCorrespondence',[])}
    if not wanted:return []
    require(len(wanted)<=8,'PP1_PEDIGREE_CAPTURE_LIMIT')
    root=Path(library)/'papers'/source['pmcid']/'pedigree-figures'
    captured=[]
    for figure in sorted(root.glob('*/source.json'))[:8]:
        for pair in sorted((figure.parent/'reviews').glob('*.json'))[:8]:
            raw=bounded(pair,4*1024*1024);pin=digest(raw)
            if pin not in wanted:continue
            from pp1_pedigree import verify_pair
            verify_pair(source,figure,pair)
            folder=Path(output)/'pedigree-originals'/pin[:24];folder.mkdir(parents=True,exist_ok=True)
            row={}
            for key,path,name,limit in [('source',figure,'source.json',65536),
                    ('image',figure.parent/'original.bin','original.bin',4*1024*1024),('pair',pair,'pair.json',4*1024*1024)]:
                body=bounded(path,limit);dest=folder/name
                require(not dest.exists() or dest.read_bytes()==body,'PP1_PEDIGREE_CAPTURE_CHANGED')
                dest.write_bytes(body);row[key]={'path':str(dest.resolve()),'sha256':digest(body)}
            captured.append(row);wanted.remove(pin)
    require(not wanted,'PP1_PEDIGREE_CAPTURE_MISSING')
    return captured


def replay_paths(root, work, entries):
    require(isinstance(entries,list) and len(entries)<=8,'PP1_PEDIGREE_CAPTURE_LIMIT')
    out=[]
    for row in entries:
        require(set(row)=={'source','image','pair'},'PP1_PEDIGREE_CAPTURE_SCHEMA')
        paths={}
        for key,name,limit in [('source','source.json',65536),('image','original.bin',4*1024*1024),('pair','pair.json',4*1024*1024)]:
            p=(Path(root)/row[key]['path']).resolve(strict=True)
            require(p.is_relative_to(Path(work).resolve(strict=True)) and p.name==name,'PP1_PEDIGREE_CAPTURE_PATH')
            require(digest(bounded(p,limit))==row[key]['sha256'],'PP1_PEDIGREE_CAPTURE_CHANGED')
            paths[key]=p
        require(len({p.parent for p in paths.values()})==1,'PP1_PEDIGREE_CAPTURE_PATH')
        out.append((paths['source'],paths['pair']))
    return out
