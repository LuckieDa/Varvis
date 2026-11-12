"""Synthetic drawings and source contracts, no patient or external API calls."""
from copy import deepcopy
import io
from pathlib import Path
import tempfile
import unittest

from pp1_pedigree import pillow, solid_parent_line, consensus, VERSION
from pp1_family_correspondence import merge_drawing, closed_groups
from model_literature_contract import canonical,digest,parse


def normalized(candidate):
    value=deepcopy(candidate)
    def box(b):return [round(b[1]*2.5),round(b[0]*2.5),round(b[3]*2.5),round(b[2]*2.5)]
    value['panelBox']=box(value['panelBox'])
    for n in value['nodes']:n['symbolBox']=box(n['symbolBox'])
    return value


class Drawing(unittest.TestCase):
    def test_image_receipts_cache_and_tamper(self):
        from pp1_pedigree import register,run,verify_pair,retain_review,register_view,load_figure
        from test_bundle_model import configuration,response
        im,nodes=self.diagram();out=io.BytesIO();im.save(out,format='WEBP',lossless=True);raw=out.getvalue()
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);paper=root/'papers/PMC123';(paper/'source-bundles').mkdir(parents=True)
            xml=b'<article xmlns:xlink="http://www.w3.org/1999/xlink"><fig id="F2"><caption>Pedigree diagram.</caption><alternatives><graphic content-type="image" xlink:href="drawing.jpg"/><graphic content-type="thumb" xlink:href="drawing.gif"/></alternatives></fig></article>'
            (paper/'main.xml').write_bytes(xml)
            bundle={'main':{'path':'main.xml'}};pin=digest(canonical(bundle));(paper/'source-bundles'/(pin+'.json')).write_bytes(canonical(bundle))
            source={'pmcid':'PMC123','bundleSha256':pin,'main':{'sourceSha256':digest(xml)},'units':[{'text':'GENE1 c.1A>G'}]}
            image=root/'image.webp';image.write_bytes(raw)
            retrieval={'status':200,'sha256':digest(raw),'bytes':len(raw),'finalUrl':'https://www.frontiersin.org/files/drawing.webp'}
            figure=register(root,source,image,retrieval)
            cfg=configuration();cfgpath=root/'cfg.json';cfgpath.write_bytes(canonical(cfg));(root/'key.txt').write_bytes(b'SYNTHETIC_TEST_KEY')
            target={'geneSymbol':'GENE1','variantAliases':['c.1A>G']}
            candidate={'schemaVersion':VERSION,'familyLabel':'Family X','panelBox':[0,0,400,400],
                       'nodes':nodes,'parentEdges':[{'parent':'I-1','child':'II-1'}],'issues':[]}
            candidate=normalized(candidate)
            calls=[]
            def transport(c,k,body):calls.append(parse(body));return response(candidate)
            limited=deepcopy(cfg);limited['maxCalls']=1
            with self.assertRaisesRegex(ValueError,'INDEPENDENT_PAIR_CALL_CAPACITY'):
                run(source,figure,'Family X',target,limited,cfgpath,root/'limited-budget',root/'limited',execute=True,transport=transport)
            self.assertFalse(calls)
            result=run(source,figure,'Family X',target,cfg,cfgpath,root/'budget',root/'first',execute=True,transport=transport)
            self.assertEqual(2,len(calls));self.assertNotEqual(calls[0],calls[1])
            self.assertIn('full strict output schema',calls[0]['messages'][0]['content'])
            self.assertTrue(parse(calls[0]['messages'][1]['content'][0]['text'])['coordinateSystem']['normalizedCoordinatesAllowed'])
            graph,path=retain_review(source,figure,result['pairPath']);self.assertEqual(1,len(graph['parentEdges']))
            from pp1_pedigree_schedule import review,next_figure
            import time
            item={'figure':figure,'family':'Family X','key':'synthetic'}
            def forbidden(*args):raise AssertionError('unexpected model call')
            checked=review(item,source,target,cfg,cfgpath,root/'budget',root/'scheduled',execute=False,
                deadline=time.monotonic()+1,remaining_calls=0,transport=forbidden,cancelled=lambda:False,on_call=forbidden)
            self.assertEqual(('VERIFIED',0),(checked['status'],checked['modelAttempts']))
            miss=review(item,source,target,cfg,cfgpath,root/'empty-budget',root/'disabled',execute=False,
                deadline=time.monotonic()+600,remaining_calls=8,transport=forbidden,cancelled=lambda:False,on_call=forbidden)
            self.assertEqual('PP1_PEDIGREE_MODEL_DISABLED_CACHE_MISSING',miss['reasonCode'])
            low=review(item,source,target,cfg,cfgpath,root/'empty-budget',root/'limited',execute=True,
                deadline=time.monotonic()+600,remaining_calls=1,transport=forbidden,cancelled=lambda:False,on_call=forbidden)
            self.assertEqual('PP1_PEDIGREE_PAIR_CALL_CAPACITY',low['reasonCode'])
            charged=[]
            new=review(item,source,target,cfg,cfgpath,root/'new-budget',root/'new-scheduled',execute=True,
                deadline=time.monotonic()+600,remaining_calls=2,transport=lambda *args:response(candidate),cancelled=lambda:False,on_call=lambda:charged.append(1))
            self.assertEqual(('VERIFIED',2),(new['status'],len(charged)))
            from pp1_pedigree_delivery import freeze,replay_paths
            frozen=freeze(root,source,{'memberCorrespondence':[{'sourceRef':{'visualPairSha256':digest(path.read_bytes())}}]},root/'capture')
            ff,pp=replay_paths(root,root/'capture',frozen)[0]
            self.assertEqual(graph,verify_pair(source,ff,pp)[0])
            changed=ff.parent/'original.bin';changed.write_bytes(changed.read_bytes()+b'x')
            with self.assertRaisesRegex(ValueError,'CAPTURE_CHANGED'):replay_paths(root,root/'capture',frozen)
            view=register_view(source,figure,root/'first/EXTRACT.json')
            record,view_image,unused=load_figure(source,view)
            self.assertEqual([0,0,400,400],record['viewBox'])
            self.assertEqual(digest(raw),record['imageSha256'])
            self.assertEqual((400,400),view_image.size)
            self.assertLess(len(str(view.parent/'reviews'/('a'*64+'.json'))),260)
            again=run(source,figure,'Family X',target,cfg,cfgpath,root/'budget',root/'cached',transport=transport)
            self.assertEqual(0,again['modelAttempts']);self.assertEqual(2,len(calls))
            original=Path(result['pairPath']).read_bytes();bad=parse(original);bad['reads'][0]['candidate']['nodes'][0]['id']='I-9'
            (root/'bad.json').write_bytes(canonical(bad))
            with self.assertRaisesRegex(ValueError,'CANDIDATE_CHANGED'):verify_pair(source,figure,root/'bad.json')
            bad=parse(original);bad['reads'][0]['binding']['implementationSha256']='f'*64
            (root/'bad.json').write_bytes(canonical(bad))
            with self.assertRaisesRegex(ValueError,'IMPLEMENTATION_OR_STAGE_CHANGED'):verify_pair(source,figure,root/'bad.json')
            # A known historical producer still needs its exact request/receipt.
            bad=parse(original);bad['reads'][0]['binding']['implementationSha256']='a94ee70ac5ed6484af01230610b687fe3f902e1cafa888d642fa49c9c9c3dbbc'
            (root/'bad.json').write_bytes(canonical(bad))
            with self.assertRaisesRegex(ValueError,'REQUEST_CHANGED'):verify_pair(source,figure,root/'bad.json')
            wrong={**source,'bundleSha256':'a'*64}
            with self.assertRaisesRegex(ValueError,'SOURCE_MISMATCH'):verify_pair(wrong,figure,result['pairPath'])
            (figure.parent/'original.bin').write_bytes(raw+b'changed')
            with self.assertRaisesRegex(ValueError,'IMAGE_CHANGED'):verify_pair(source,figure,result['pairPath'])

    def test_schema_rejects_invented_format_coordinates_and_members(self):
        from pp1_pedigree import check_candidate
        im,nodes=self.diagram()
        base={'schemaVersion':VERSION,'familyLabel':'Family X','panelBox':[0,0,400,400],
              'nodes':nodes,'parentEdges':[],'issues':[]}
        for change in ('float','bool','extra','duplicate','unknown-member','alternate-schema'):
            with self.subTest(change=change):
                value=deepcopy(base)
                if change=='float':value['panelBox'][0]=0.5
                if change=='bool':value['panelBox'][0]=False
                if change=='extra':value['nodes'][0]['affected']=True
                if change=='duplicate':value['nodes'].append(value['nodes'][0])
                if change=='unknown-member':value['parentEdges']=[{'parent':'missing','child':'II-1'}]
                if change=='alternate-schema':value['individuals']=value.pop('nodes')
                with self.assertRaises(ValueError):check_candidate(value)

    def diagram(self, style='solid'):
        Image=pillow();from PIL import ImageDraw
        image=Image.new('RGB',(400,400),'white');draw=ImageDraw.Draw(image)
        nodes=[{'id':'I-1','sex':'MALE','symbolBox':[50,40,90,80],'variantText':'c.1A>G'},
               {'id':'I-2','sex':'FEMALE','symbolBox':[210,40,250,80],'variantText':'UNKNOWN'},
               {'id':'II-1','sex':'FEMALE','symbolBox':[130,230,170,270],'variantText':'c.1A>G'}]
        for n in nodes:draw.rectangle(n['symbolBox'],outline='black',width=2)
        draw.line([(90,60),(210,60)],fill='black',width=2)
        if style=='solid':draw.line([(150,60),(150,230)],fill='black',width=2)
        elif style=='dotted':
            for y in range(60,230,12):draw.line([(150,y),(150,y+4)],fill='black',width=2)
        elif style=='diagonal':draw.line([(170,60),(150,230)],fill='black',width=2)
        return image,nodes

    def test_solid_parent_connection_and_missing_dotted_diagonal_refusal(self):
        for style,expected in [('solid',True),('missing',False),('dotted',False),('diagonal',False)]:
            with self.subTest(style=style):
                im,n=self.diagram(style)
                self.assertEqual(expected,solid_parent_line(im,n[0],n[2],n))
                self.assertFalse(solid_parent_line(im,n[0],n[1],n))
                self.assertFalse(solid_parent_line(im,n[2],n[0],n))

    def test_read_disagreement_and_unconnected_edge_are_visible(self):
        im,n=self.diagram();a={'schemaVersion':VERSION,'familyLabel':'Family X','panelBox':[0,0,400,400],
            'nodes':n,'parentEdges':[{'parent':'I-1','child':'II-1'}],'issues':[]}
        a=normalized(a)
        rec={'imageSha256':'a'*64,'figureId':'F2'}
        self.assertEqual(a['parentEdges'],consensus(rec,im,[a,deepcopy(a)])['parentEdges'])
        b=deepcopy(a);b['nodes'][0]['variantText']='c.1A>T'
        r=consensus(rec,im,[a,b]);self.assertFalse(r['parentEdges']);self.assertTrue(r['issues'])
        broken,_=self.diagram('missing')
        self.assertFalse(consensus(rec,broken,[a,deepcopy(a)])['parentEdges'])
        special=deepcopy(a);special['issues']=['Adoption brackets are visible.']
        with self.assertRaisesRegex(ValueError,'RELATIONSHIP_CONVENTION'):consensus(rec,im,[a,special])
        empty=im.copy();from PIL import ImageDraw
        ImageDraw.Draw(empty).rectangle([0,0,400,400],fill='white')
        rejected=consensus(rec,empty,[a,deepcopy(a)])
        self.assertFalse(rejected['nodes']);self.assertFalse(rejected['parentEdges'])
        self.assertTrue(any('SYMBOL_LOCATION_UNVERIFIED' in i for i in rejected['issues']))

    def test_intermediate_person_cannot_be_bypassed_on_symbol_outline(self):
        im,n=self.diagram();from PIL import ImageDraw
        draw=ImageDraw.Draw(im)
        middle={'id':'I-A','sex':'UNKNOWN','symbolBox':[140,120,160,150],'variantText':'UNKNOWN'}
        draw.rectangle(middle['symbolBox'],fill='white',outline='black',width=2)
        self.assertFalse(solid_parent_line(im,n[0],n[2],n+[middle]))

    def test_relationship_agreement_uses_unique_source_grounded_location(self):
        im,n=self.diagram();a={'schemaVersion':VERSION,'familyLabel':'Family X','panelBox':[0,0,400,400],
            'nodes':n,'parentEdges':[{'parent':'I-1','child':'II-1'}],'issues':[]}
        # The sex here is just the fixture's square convention, not disease/GT.
        for node in a['nodes']:node['sex']='MALE'
        a=normalized(a);b=deepcopy(a)
        b['nodes'][2]['symbolBox']=[575,225,675,325]  # Same person/edge, bad image location.
        record={'imageSha256':'a'*64,'figureId':'F2'}
        graph=consensus(record,im,[a,b])
        self.assertEqual(a['parentEdges'],graph['parentEdges'])
        self.assertTrue(any('LOCATION_RESOLVED_FROM_SOURCE' in i for i in graph['issues']))
        self.assertEqual(a['parentEdges'],consensus(record,im,[b,a])['parentEdges'])
        self.assertFalse(consensus(record,im,[b,deepcopy(b)])['parentEdges'])
        # Two actual distinct symbols cannot be resolved by picking a nicer result.
        from PIL import ImageDraw
        ImageDraw.Draw(im).rectangle([90,230,130,270],outline='black',width=2)
        self.assertFalse(consensus(record,im,[a,b])['parentEdges'])
        # Relationship disagreement still blocks even if the line exists.
        b=deepcopy(a);b['parentEdges']=[]
        self.assertFalse(consensus(record,im,[a,b])['parentEdges'])

    def test_aliases_require_unique_actual_parent_paths_and_no_symbol_diagnosis(self):
        q={'identity':{'status':'LOCAL_IDENTITY_VERIFIED'},'families':{'f':{'sourceFamilyLabel':'Family 8',
            'members':{'III-4':'p','mother':'m','grandfather':'g'}}},'observations':[], 'unresolved':[]}
        for label,key in [('III-4','p'),('mother','m'),('grandfather','g')]:
            q['observations'].append({'id':key,'familyKey':'f','memberKey':key,'memberLabel':label,'kind':'diagnosis',
                'value':'TEST disease','claimIds':[],'sourceRefs':[{'quote':'Proband is III-4.'},{'quote':'Her mother and grandfather are TEST disease patients.'}]})
        q['observations'].append({'id':'prob','familyKey':'f','memberKey':'p','memberLabel':'III-4',
             'kind':'proband','value':True,'claimIds':[],'sourceRefs':[]})
        source={'pmcid':'PMC123','bundleSha256':'a'*64};inventory={'target':{'variantAliases':['c.1A>G']}}
        graph={'familyLabel':'Family eight','imageSha256':'b'*64,'figureId':'F2','method':'SYNTHETIC_TEST', 'issues':[],
            'nodes':[{'id':id,'sex':sex,'symbolBox':[0,0,10,10],'variantText':'c.1A>G'} for id,sex in
                     [('I-3','MALE'),('II-2','FEMALE'),('III-4','FEMALE')]],
            'parentEdges':[{'parent':'I-3','child':'II-2'},{'parent':'II-2','child':'III-4'}]}
        out=deepcopy(q);merge_drawing(out,source,inventory,graph,'c'*64)
        self.assertEqual(2,len([o for o in out['observations'] if o['kind']=='parentOf']))
        self.assertEqual(3,len([o for o in out['observations'] if o['kind']=='genotype']))
        self.assertEqual(3,len([o for o in out['observations'] if o['kind']=='diagnosis']))
        missing=deepcopy(graph);missing['parentEdges']=missing['parentEdges'][1:]
        out=deepcopy(q);merge_drawing(out,source,inventory,missing,'c'*64)
        self.assertNotIn('grandfather',out['memberCorrespondence'][0]['aliases'])
        cyclic=deepcopy(graph);cyclic['parentEdges'].append({'parent':'III-4','child':'I-3'})
        with self.assertRaisesRegex(ValueError,'CYCLIC_GRAPH'):merge_drawing(deepcopy(q),source,inventory,cyclic,'c'*64)

    def test_closed_collective_requires_named_diagnosed_members_and_same_unit(self):
        text='A and B are TEST disease patients. All two TEST disease patients in this family carried c.1A>G.'
        quote='All two TEST disease patients in this family carried c.1A>G.'
        source={'units':[{'unitId':'u','text':text}]}
        inv={'target':{'variantAliases':['c.1A>G']},'facts':[{'raw':{'fieldId':'F06.3'},'familyKey':'f',
            'claimId':'claim','sourceAudit':{'verdict':'SUPPORTED'},'issues':[], 'sourceRefs':[{'unitId':'u','quote':quote}]}]}
        q={'identity':{},'families':{'f':{'members':{'A':'a','B':'b'}}},'observations':[]}
        q['identity']={'status':'VERIFIED'}
        for label,key in [('A','a'),('B','b')]:q['observations'].append({'id':key,'familyKey':'f','memberKey':key,
            'memberLabel':label,'kind':'diagnosis','value':'TEST disease','sourceRefs':[{'unitId':'u','quote':text}],'claimIds':[]})
        yes=deepcopy(q);closed_groups(source,inv,yes);self.assertEqual(2,len([o for o in yes['observations'] if o['kind']=='genotype']))
        for change in ('count','different-unit','uncertain','no-closure'):
            a,b,c=deepcopy(source),deepcopy(inv),deepcopy(q)
            if change=='count':b['facts'][0]['sourceRefs'][0]['quote']=quote.replace('two','three');a['units'][0]['text']=text.replace('two','three')
            if change=='different-unit':c['observations'][0]['sourceRefs'][0]['unitId']='other'
            if change=='uncertain':a['units'][0]['text']='Whether '+quote
            if change=='no-closure':a['units'][0]['text']=text.replace(' in this family','');b['facts'][0]['sourceRefs'][0]['quote']=quote.replace(' in this family','')
            closed_groups(a,b,c);self.assertFalse([o for o in c['observations'] if o['kind']=='genotype'],change)

if __name__=='__main__':unittest.main()
