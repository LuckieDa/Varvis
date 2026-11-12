"""Read target-variant passages first; do not silently discard the rest of a paper.

This is scheduling, never identity or scientific qualification. Original chunk
indices, source units and pending coverage remain unchanged.
"""
import re
from model_literature_contract import parse


def order(requests,target):
    aliases=set()
    for value in target['variantAliases']:
        value=value.split(':')[-1]
        aliases.add(re.sub(r'\s+','',value).casefold())
        if value.startswith(('c.','p.')):aliases.add(re.sub(r'\s+','',value[2:]).casefold())
        # VEP commonly emits Ala403Val while articles use A403V. This is
        # notation-only scheduling, not a protein-to-genomic identity mapping.
        if value.startswith('p.'):
            from literature_allele_identity import protein
            short=protein(value)
            if short:
                aliases.add(short.casefold());aliases.add('p.'+short.casefold())
    patterns=[re.compile(r'(?<![a-z0-9])'+r'\s*'.join(re.escape(char) for char in value)+r'(?![a-z0-9])') for value in aliases]
    ranked=[]
    for index,request in enumerate(requests):
        content=parse(request['messages'][1]['content']); targets=set(content['workUnit']['targetUnitIds'])
        hits={u['unitId'] for u in content['units'] if any(p.search(u['text'].casefold()) for p in patterns)}
        priority=0 if hits&targets else 1 if hits else 2
        if priority==0:
            from pm3_single_case import hints
            # Read an explicit current-study genotype before abstract mentions;
            # retain all other chunks and their original indices for continuation.
            if any(h['unitId'] in targets for h in hints(content['units'])):priority=-1
        if priority==2 and targets and all(any(part in unit for part in ('/author-notes[','/ack[','/permissions[','/ref-list[')) for unit in targets):
            priority=3
        ranked.append((priority,index))
    return [index for _,index in sorted(ranked)]
