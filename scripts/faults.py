"""Fault injection against this project's demo containers. Preserves volumes."""
import pathlib, subprocess, time, uuid, json, base64, urllib.request
from demo import request, verified, wait
def compose(*args):
 subprocess.run(['docker','compose',*args],check=True,stdout=subprocess.DEVNULL)
def sql(query):
 return subprocess.check_output(['docker','compose','exec','-T','postgres','psql','-U','trustpay','-d','trustpay','-Atc',query],text=True).strip()
def healthy():
 for _ in range(90):
  try:
   if request('GET','/actuator/health',user=None)[0]==200:return
  except Exception:pass
  time.sleep(1)
 raise AssertionError('app did not become healthy')
customer=verified();body={'customerId':customer,'amount':10000,'currency':'EUR'}
try:
 compose('stop','rabbit')
 code,p=request('POST','/api/payments',body,key=str(uuid.uuid4()));assert code==202
 id=p['id'];time.sleep(2)
 assert sql("SELECT count(*) FROM outbox WHERE payment_id='"+id+"' AND published=false")=='1'
 print('PASS broker outage retains transactional outbox',id,flush=True)
finally:compose('start','rabbit')
wait(id,'SUCCEEDED',90)
print('PASS broker recovery completes original payment',flush=True)
event=sql("SELECT id FROM outbox WHERE payment_id='"+id+"'")
for _ in range(2):
 payload={'properties':{'delivery_mode':2},'routing_key':'trustpay.payments','payload':event+':'+id,'payload_encoding':'string'}
 req=urllib.request.Request('http://localhost:15672/api/exchanges/%2F/amq.default/publish',data=json.dumps(payload).encode(),headers={'Authorization':'Basic '+base64.b64encode(b'trustpay:trustpay').decode(),'Content-Type':'application/json'})
 with urllib.request.urlopen(req) as response:assert json.loads(response.read())['routed']
time.sleep(2)
assert sql("SELECT count(*) FROM ledger_transactions WHERE payment_id='"+id+"'")=='1'
print('PASS replaying actual broker event does not duplicate ledger',flush=True)
code,p=request('POST','/api/payments',{**body,'amount':9900},key=str(uuid.uuid4()));assert code==202
id=p['id']
for _ in range(100):
 _,current=request('GET','/api/payments/'+id)
 if current['status']=='PROCESSING':break
 time.sleep(.05)
else:raise AssertionError('did not observe PROCESSING before crash')
try:compose('kill','-s','SIGKILL','app')
finally:compose('start','app')
healthy();wait(id,'SUCCEEDED',90)
assert sql("SELECT count(*) FROM ledger_transactions WHERE payment_id='"+id+"'")=='1'
print('PASS abrupt application crash recovers PROCESSING payment exactly once',id,flush=True)
print('ALL FAULT ASSERTIONS PASSED',flush=True)
