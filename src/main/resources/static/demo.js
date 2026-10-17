'use strict';
let token, customer, payment, lastRequest, lastKey, busy=false;
const el=id=>document.getElementById(id);
const log=(label,data)=>{el('results').textContent=label+'\n'+JSON.stringify(data,null,2)+'\n\n'+el('results').textContent.slice(0,18000)};
async function api(path,method='GET',body,headers={},auth=token){const res=await fetch(path,{method,headers:{...(auth?{Authorization:'Bearer '+auth}:{}),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined});let value;const text=await res.text();try{value=JSON.parse(text)}catch{value=text}log(method+' '+path+' → HTTP '+res.status,value);return {status:res.status,value}}
const enable=(ids,on=true)=>ids.forEach(id=>el(id).disabled=!on);
function select(p){payment=p;el('payment').textContent='Payment '+p.id+' / '+p.status;enable(['refresh','replay','callback','isolation'])}
function action(id,fn){el(id).addEventListener('click',async()=>{if(busy)return;busy=true;const label=el(id).textContent;el(id).textContent='Working…';el(id).disabled=true;document.querySelector('main').setAttribute('aria-busy','true');try{await fn()}catch(e){log('Request failed',{message:e.message})}finally{busy=false;el(id).textContent=label;el(id).disabled=false;document.querySelector('main').removeAttribute('aria-busy')}})}
action('start',async()=>{const r=await api('/api/demo/session','POST',undefined,{},null);if(r.status!==200)return;token=r.value.access_token;customer=payment=lastRequest=lastKey=undefined;el('session').textContent='Session active / '+r.value.merchant+' / expires in 15 minutes';enable(['customer','copy']);enable(['blocked','verify','success','failed','timeout','refresh','replay','callback','isolation'],false);el('payment').textContent='No payment selected.'});
action('customer',async()=>{const r=await api('/api/customers','POST');if(r.status===201){customer=r.value;enable(['blocked','verify']);enable(['success','failed','timeout'],false)}});
action('blocked',()=>api('/api/payments','POST',{customerId:customer.id,amount:10000,currency:'EUR'},{'Idempotency-Key':crypto.randomUUID()}));
action('verify',async()=>{const r=await api('/api/demo/customers/'+customer.id+'/verify','PUT');if(r.status===200)enable(['success','failed','timeout'])});
async function pay(amount){lastRequest={customerId:customer.id,amount,currency:'EUR'};lastKey=crypto.randomUUID();const r=await api('/api/payments','POST',lastRequest,{'Idempotency-Key':lastKey});if(r.status===202)select(r.value)}
action('success',()=>pay(10000));action('failed',()=>pay(1300));action('timeout',()=>pay(9900));
action('refresh',async()=>{const r=await api('/api/payments/'+payment.id);if(r.status===200)select(r.value);await api('/api/payments/'+payment.id+'/ledger');await api('/api/payments/'+payment.id+'/audit')});
action('replay',async()=>{const old=payment.id;const r=await api('/api/payments','POST',lastRequest,{'Idempotency-Key':lastKey});log('Idempotency assertion',{samePayment:r.value.id===old,paymentId:r.value.id});if(r.status===202)select(r.value)});
action('callback',()=>api('/api/demo/payments/'+payment.id+'/repeat-callback','POST'));
action('isolation',async()=>{const other=await api('/api/demo/session','POST',undefined,{},null);if(other.status===200){const r=await api('/api/payments/'+payment.id,'GET',undefined,{},other.value.access_token);log('Merchant isolation assertion',{passed:r.status===404})}});
action('copy',async()=>{await navigator.clipboard.writeText(token);el('session').textContent='JWT copied. In Swagger choose Authorize, then paste the token. It expires 15 minutes after session creation.'});
