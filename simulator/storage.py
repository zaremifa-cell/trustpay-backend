import json, os, sqlite3
class Store:
 def __init__(self):
  self.uri=os.environ.get('PROVIDER_DATABASE_URL')
  if not self.uri:
   self.path=os.environ.get('SIMULATOR_DB','/data/provider.db');os.makedirs(os.path.dirname(self.path),exist_ok=True)
   with sqlite3.connect(self.path) as db:db.execute('CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY,payload TEXT NOT NULL)')
 def connect(self):
  if self.uri:
   import psycopg
   return psycopg.connect(self.uri,connect_timeout=10)
  return sqlite3.connect(self.path)
 def get(self,id):
  with self.connect() as db:
   row=db.execute('SELECT payload FROM provider_records WHERE payment_id=%s' if self.uri else 'SELECT payload FROM payments WHERE id=?',(id,)).fetchone()
  return (row[0] if self.uri else json.loads(row[0])) if row else None
 def report(self):
  with self.connect() as db:rows=db.execute('SELECT payload FROM provider_records ORDER BY created_at DESC LIMIT 1000' if self.uri else 'SELECT payload FROM payments ORDER BY rowid DESC LIMIT 1000').fetchall()
  return [row[0] if self.uri else json.loads(row[0]) for row in rows]
 def submit(self,value):
  result={**value,'status':'FAILED' if value['amount']==1300 else 'SUCCEEDED'}
  with self.connect() as db:
   if self.uri:db.execute('INSERT INTO provider_records(payment_id,payload) VALUES (%s,%s::jsonb) ON CONFLICT DO NOTHING',(value['paymentId'],json.dumps(result)))
   else:db.execute('INSERT OR IGNORE INTO payments VALUES (?,?)',(value['paymentId'],json.dumps(result)))
  return self.get(value['paymentId'])
store=Store()
