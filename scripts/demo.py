"""Live end-to-end assertions. Requires the full Compose stack on localhost."""
import base64, concurrent.futures, json, time, urllib.request, urllib.error, uuid
BASE='http://localhost:8080'
def request(method,path,body=None,user='merchant',key=None,secret=None):
 headers={'Content-Type':'application/json'}
 credentials={'merchant':'merchant-demo','other':'other-demo','ops':'ops-demo'}
 if user:headers['Authorization']='Basic '+base64.b64encode((user+':'+credentials[user]).encode()).decode()
 if key:headers['Idempotency-Key']=key
 if secret:headers['X-Provider-Secret']=secret
 req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=10) as r:
   data=r.read();return r.status,json.loads(data) if data else None
 except urllib.error.HTTPError as e:return e.code,None
def verified():
 code,c=request('POST','/api/customers');assert code==201,(code,c)
 assert request('PUT','/api/ops/customers/'+c['id']+'/identity',{'status':'VERIFIED'},'ops')[0]==200
 return c['id']
def wait(id,expected,seconds=60):
 end=time.time()+seconds
 while time.time()<end:
  code,p=request('GET','/api/payments/'+id)
  if code==200 and p['status']==expected:return p
  time.sleep(.5)
 raise AssertionError((id,expected,p))
def main():
 code,pending=request('POST','/api/customers');assert code==201
 assert request('POST','/api/payments',{'customerId':pending['id'],'amount':10000,'currency':'EUR'},key=str(uuid.uuid4()))[0]==422
 print('PASS unverified identity rejected')
 customer=verified();key=str(uuid.uuid4());body={'customerId':customer,'amount':10000,'currency':'EUR'}
 with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
  results=list(pool.map(lambda _:request('POST','/api/payments',body,key=key),range(20)))
 assert all(code==202 for code,_ in results),results
 ids={p['id'] for _,p in results};assert len(ids)==1,ids
 id=ids.pop();wait(id,'SUCCEEDED')
 print('PASS 20 simultaneous retries -> one successful payment',id)
 assert request('POST','/api/payments',{**body,'amount':10001},key=key)[0]==409
 assert request('GET','/api/payments/'+id,user='other')[0]==404
 assert request('GET','/api/payments/'+id,user=None)[0]==401
 assert request('PUT','/api/ops/customers/'+customer+'/identity',{'status':'VERIFIED'})[0]==403
 print('PASS payload conflict, ownership and role restrictions')
 code,token=request('POST','/api/token');assert code==200
 req=urllib.request.Request(BASE+'/api/payments/'+id,headers={'Authorization':'Bearer '+token['access_token']})
 with urllib.request.urlopen(req) as response:assert json.loads(response.read())['id']==id
 print('PASS signed JWT authentication')
 callback={'paymentId':id,'status':'SUCCEEDED','amount':10000,'currency':'EUR'}
 assert request('POST','/api/provider/webhook',callback,user=None,secret='wrong')[0]==401
 for _ in range(2):assert request('POST','/api/provider/webhook',callback,user=None,secret='local-provider-secret')[0]==200
 _,ledger=request('GET','/api/payments/'+id+'/ledger');assert len(ledger)==1 and ledger[0]['debit_amount']==ledger[0]['credit_amount']
 print('PASS authenticated duplicate callbacks -> one balanced ledger transaction')
 for amount,expected in [(1300,'FAILED'),(9900,'SUCCEEDED')]:
  code,p=request('POST','/api/payments',{**body,'amount':amount},key=str(uuid.uuid4()));assert code==202
  wait(p['id'],expected);print('PASS provider scenario',amount,expected,p['id'])
 print('ALL LIVE ASSERTIONS PASSED')

if __name__=='__main__':main()
