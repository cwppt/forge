import copy,unittest
from structured_parity import compare

def fixture():
    rows=[('BEGIN',None,{}),('ATTACK_ASSIGNMENTS',None,{'1':{'defender':'player:2','blockers':[3]}}),
          ('BATCH_BEGIN',1,{'combat':True,'requested':{'1->card:3':7,'2->player:2':7}}),
          ('DAMAGE',1,{'source':1,'recipient':'card:3','amount':7}),('DAMAGE',1,{'source':2,'recipient':'player:2','amount':7}),
          ('TRIGGER_READY',1,{'host':3,'mode':'DamageDone'}),('TRIGGER_READY',1,{'host':4,'mode':'DamageDone'}),
          ('BATCH_END',1,{'life':13,'damage':{'3':7}}),('RESOLVE_START',None,{'host':3}),('RESOLVED',None,{'host':3}),
          ('END',None,{'life':13})]
    return [dict(eventIndex=i,type=t,batch=b,data=d,turn=53,phase='COMBAT_DAMAGE') for i,(t,b,d) in enumerate(rows)]

class ParityTests(unittest.TestCase):
    def test_identical(self):compare(fixture(),fixture())
    def test_only_simultaneous_delivery_permutation_is_allowed(self):
        a=fixture();b=copy.deepcopy(a);b[3]['data'],b[4]['data']=b[4]['data'],b[3]['data'];compare(a,b)
    def test_changed_assignments_recipients_amounts_states_and_triggers_fail(self):
        for i,field,value in [(1,'1',{'defender':'player:4','blockers':[3]}),(3,'recipient','card:9'),(3,'amount',6),(5,'host',9),(7,'life',12),(8,'host',8),(10,'life',12)]:
            with self.subTest(i=i,field=field):
                a=fixture();b=copy.deepcopy(a);b[i]['data'][field]=value
                with self.assertRaises(AssertionError):compare(a,b)
    def test_trigger_and_resolution_order_is_sensitive(self):
        for x,y in [(5,6),(8,9)]:
            a=fixture();b=copy.deepcopy(a)
            b[x]['type'],b[y]['type']=b[y]['type'],b[x]['type'];b[x]['data'],b[y]['data']=b[y]['data'],b[x]['data']
            with self.assertRaises(AssertionError):compare(a,b)
    def test_noncombat_delivery_order_is_sensitive(self):
        a=fixture();a[2]['data']['combat']=False;b=copy.deepcopy(a);b[3]['data'],b[4]['data']=b[4]['data'],b[3]['data']
        with self.assertRaises(AssertionError):compare(a,b)
    def test_damage_cannot_cross_trigger_or_batch_boundaries(self):
        a=fixture();b=copy.deepcopy(a);b[4]['type'],b[5]['type']=b[5]['type'],b[4]['type'];b[4]['data'],b[5]['data']=b[5]['data'],b[4]['data']
        with self.assertRaises(AssertionError):compare(a,b)
    def test_incomplete_or_duplicate_trace_is_rejected(self):
        for b in [fixture()[:-1],fixture()[1:],fixture()+fixture()[-1:]]:
            with self.assertRaises(AssertionError):compare(fixture(),b)
    def test_blocker_order_is_preserved(self):
        a=fixture();a[1]['data']['1']['blockers']=[3,4];b=copy.deepcopy(a);b[1]['data']['1']['blockers']=[4,3]
        with self.assertRaises(AssertionError):compare(a,b)

if __name__=='__main__':unittest.main()
