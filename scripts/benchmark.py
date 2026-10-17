"""Small HTTP measurement; no claim of production capacity. Use JWT, not per-request BCrypt."""
import base64, concurrent.futures, json, os, platform, statistics, time, urllib.request
base=os.environ.get('BENCHMARK_URL','http://localhost:8080')
req=urllib.request.Request(base+'/api/token',data=b'',headers={'Authorization':'Basic '+base64.b64encode(b'merchant:merchant-demo').decode()},method='POST')
with urllib.request.urlopen(req) as r:token=json.loads(r.read())['access_token']
def call(_):
 start=time.perf_counter()
 req=urllib.request.Request(base+'/api/payments?limit=20',headers={'Authorization':'Bearer '+token})
 with urllib.request.urlopen(req,timeout=20) as r:assert r.status==200;r.read()
 return (time.perf_counter()-start)*1000
for i in range(50):call(i)
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:values=sorted(pool.map(call,range(1000)))
elapsed=time.perf_counter()-start
print(json.dumps({'platform':platform.platform(),'endpoint':'GET /api/payments?limit=20','base_url':base,'requests':len(values),'concurrency':16,'warmup':50,'elapsed_seconds':round(elapsed,3),'requests_per_second':round(len(values)/elapsed,1),'p50_ms':round(values[len(values)//2],3),'p95_ms':round(values[int(len(values)*.95)],3),'p99_ms':round(values[int(len(values)*.99)],3),'note':'Local synthetic read workload, Python urllib opens connections; not payment throughput or a production capacity claim.'},indent=2))
