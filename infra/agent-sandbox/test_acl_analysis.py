import copy
import unittest

import acl_analysis
import test_project_core as fixtures

TABLE = """Symbol table '.dynsym' contains 5 entries:
   Num:    Value          Size Type    Bind   Vis      Ndx Name
     0: 0000000000000000     0 NOTYPE  LOCAL  DEFAULT  UND
     1: 0000000000000000     0 FUNC    GLOBAL DEFAULT  UND acl_get_file@ACL_1.0 (3)
     2: 0000000000000000     0 FUNC    WEAK   DEFAULT  UND acl_get_file_at@ACL_2.4 (4)
     3: 0000000000000000     0 FUNC    GLOBAL DEFAULT  UND dlopen@GLIBC_2.34 (2)
     4: 0000000000001234    42 FUNC    GLOBAL DEFAULT   12 acl_set_file@@ACL_1.0
"""
TEXT = fixtures.HEADER + " 0x1 (NEEDED) Shared library: [libacl.so.1]\n" + TABLE
LEGACY = ['acl_delete_def_file','acl_extended_file','acl_get_file','acl_set_file']


class AclAnalysisTest(unittest.TestCase):
    def parse(self,text):
        return getattr(acl_analysis,'parse_acl_elf',lambda *_:None)(text)

    def test_distinguishes_versioned_imports_exports_and_lookup_without_safety_claim(self):
        result=self.parse(TEXT);self.assertIsNotNone(result)
        self.assertEqual(['libacl.so.1'],result['needed'])
        self.assertEqual(['acl_get_file@ACL_1.0'],result['legacyPathnameImports'])
        symbols={s['raw']:s for s in result['aclSymbols']}
        self.assertTrue(symbols['acl_get_file@ACL_1.0']['undefined'])
        self.assertEqual('ACL_1.0',symbols['acl_get_file@ACL_1.0']['version'])
        self.assertEqual('WEAK',symbols['acl_get_file_at@ACL_2.4']['binding'])
        self.assertFalse(symbols['acl_set_file@@ACL_1.0']['undefined'])
        self.assertEqual(['dlopen@GLIBC_2.34'],result['dynamicLookupImports'])
        self.assertEqual(['acl_get_file_at@ACL_2.4'],result['atImports'])
        self.assertNotIn('notAffected',result)

    def test_exact_legacy_name_and_valid_empty_acl_observation(self):
        text=TEXT.replace('acl_get_file@ACL_1.0','acl_get_file_extra@ACL_1.0')
        result=self.parse(text);self.assertIsNotNone(result)
        self.assertEqual([],result['legacyPathnameImports'])
        plain=TEXT.replace('acl_get_file','other_function').replace('acl_set_file','other_export')
        result=self.parse(plain);self.assertIsNotNone(result)
        self.assertEqual([],result['aclSymbols'])

    def test_missing_malformed_truncated_or_duplicate_tables_fail_closed(self):
        for text in (fixtures.HEADER,TEXT.replace('contains 5','contains 6'),TEXT.replace('     4:','     3:'),
                     TEXT.replace('WEAK','BROKEN FIELD'),TEXT+TABLE,TEXT.replace('acl_get_file@ACL_1.0','acl_get_file@@@bad'),
                     TEXT.replace('FUNC','UNKNOWN'),TEXT.replace('GLOBAL','UNKNOWN'),TEXT.replace('DEFAULT','UNKNOWN')):
            with self.subTest(text=text),self.assertRaises(ValueError):self.parse(text)

    def graph(self):
        data=[{'path':'usr/bin/cp','role':'command','package':'coreutils','sha256':'a'*64,'needed':['libacl.so.1']},
              {'path':'usr/bin/mv','role':'command','package':'coreutils','sha256':'b'*64,'needed':['libhelper.so.1']},
              {'path':'usr/bin/awk','role':'command','package':'gawk','sha256':'c'*64,'needed':[]},
              {'path':'usr/lib/x86_64-linux-gnu/libhelper.so.1','role':'library','package':'synthetic','sha256':'d'*64,'needed':['libacl.so.1']},
              {'path':'usr/lib/x86_64-linux-gnu/libacl.so.1','role':'library','package':'libacl1','sha256':'e'*64,'needed':[]}]
        for x in data:x.update(aclSymbols=[],legacyPathnameImports=[],dynamicLookupImports=[],interpreter=None)
        data[0]['legacyPathnameImports']=['acl_get_file@ACL_1.0']
        data[0]['aclSymbols']=[{'name':'acl_get_file','raw':'acl_get_file@ACL_1.0','undefined':True}]
        data[-1]['aclSymbols']=[{'name':n,'raw':n+'@@ACL_1.0','undefined':False,'kind':'FUNC','binding':'GLOBAL','visibility':'DEFAULT'} for n in LEGACY]
        manifest={'files':[{k:x[k] for k in ('path','role','package','sha256')} for x in data]}
        return data,manifest

    def mapping(self,data,manifest):
        return getattr(acl_analysis,'map_acl_consumers',lambda *_:None)(data,manifest)

    def test_maps_direct_and_transitive_needed_paths_with_positive_provider(self):
        data,manifest=self.graph();result=self.mapping(data,manifest);self.assertIsNotNone(result)
        commands={x['path']:x for x in result}
        self.assertTrue(commands['usr/bin/cp']['libAclInNeededClosure'])
        self.assertTrue(commands['usr/bin/mv']['libAclInNeededClosure'])
        self.assertFalse(commands['usr/bin/awk']['libAclInNeededClosure'])
        self.assertEqual(['acl_get_file@ACL_1.0'],commands['usr/bin/cp']['observedLegacySymbols'])
        self.assertNotIn('notAffected',commands['usr/bin/awk'])

    def test_requires_exact_elf_coverage_hash_and_real_provider_exports(self):
        data,manifest=self.graph()
        cases=[data[:-1],data+[data[0]]]
        bad=copy.deepcopy(data);bad[0]['sha256']='f'*64;cases.append(bad)
        bad=copy.deepcopy(data);bad[-1]['aclSymbols']=[];cases.append(bad)
        bad=copy.deepcopy(data);bad[0]['needed']=['missing.so'];cases.append(bad)
        for item in cases:
            with self.subTest(item=item),self.assertRaises(ValueError):self.mapping(item,manifest)

    def test_provider_requires_public_function_exports(self):
        for field,value in (("kind","OBJECT"),("binding","LOCAL"),("visibility","HIDDEN")):
            data,manifest=self.graph();data[-1]["aclSymbols"][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.mapping(data,manifest)
