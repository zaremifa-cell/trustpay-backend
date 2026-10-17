"""End-to-end check against a public TrustPay sandbox. Prints no JWTs."""
import json,time,uuid,urllib.request,urllib.error,sys
BASE=sys.argv[1].rstrip('/')
def call(path,method='GET',body=None,token=None,key=None):
 headers={}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 if body is not None:headers['Content-Type']='application/json'
 req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else (b'' if method=='POST' else None),headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=90) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  raw=e.read().decode()
  try:raw=json.loads(raw)
  except ValueError:pass
  return e.code,raw
status,session=call('/api/demo/session','POST');assert status==200,(status,str(session)[:200]);token=session['access_token'];print('PASS public session')
status,c=call('/api/customers','POST',token=token);assert status==201,(status,c)
body={'customerId':c['id'],'amount':10000,'currency':'EUR'}
status,_=call('/api/payments','POST',body,token,str(uuid.uuid4()));assert status==422,status;print('PASS identity gate')
status,_=call('/api/demo/customers/'+c['id']+'/verify','PUT',token=token);assert status==200,status
for amount,expected in [(10000,'SUCCEEDED'),(1300,'FAILED'),(9900,'SUCCEEDED')]:
 body['amount']=amount;key=str(uuid.uuid4());status,p=call('/api/payments','POST',body,token,key);assert status==202,(status,p);pid=p['id'];seen=[p['status']]
 for i in range(15):
  status,p=call('/api/payments/'+pid,token=token);assert status==200,(status,p);seen.append(p['status'])
  if p['status'] in ['SUCCEEDED','FAILED']:break
  time.sleep(2)
 assert p['status']==expected,(pid,seen)
 status,replay=call('/api/payments','POST',body,token,key);assert status==202 and replay['id']==pid
 status,result=call('/api/demo/payments/'+pid+'/repeat-callback','POST',token=token);assert status==200,(status,result)
 expected_count=1 if expected=='SUCCEEDED' else 0;assert result['ledgerTransactions']==expected_count,result
 status,ledger=call('/api/payments/'+pid+'/ledger',token=token);assert status==200 and len(ledger)==expected_count,(status,ledger)
 for row in ledger:assert row['debit_amount']==row['credit_amount'],row
 _,other=call('/api/demo/session','POST');status,_=call('/api/payments/'+pid,token=other['access_token']);assert status==404,status
 print('PASS',amount,expected,'statuses='+','.join(seen),'idempotency, duplicate settlement, ledger, isolation')
status,_=call('/api/ops/reconcile','POST',token=token);assert status==403,status;print('PASS operations access denied')
