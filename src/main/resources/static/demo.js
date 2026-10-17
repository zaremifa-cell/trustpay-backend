'use strict';
let token, customer, payment, lastRequest, lastKey, verified=false, busy=false;
const actions={};
const el=id=>document.getElementById(id);
const terminal=p=>p&&['SUCCEEDED','FAILED'].includes(p.status);
const pending=()=>payment&&!terminal(payment);
const money=amount=>new Intl.NumberFormat('en-IE',{style:'currency',currency:'EUR'}).format(amount/100);
const log=(label,data)=>{el('results').textContent=label+'\n'+JSON.stringify(data,null,2)+'\n\n'+el('results').textContent.slice(0,18000);};
function notice(title,detail,tone='info'){el('notice').dataset.tone=tone;el('notice-title').textContent=title;el('notice-detail').textContent=detail;el('notice-icon').textContent=tone==='success'?'✓':tone==='error'?'!':tone==='pending'?'…':'→';}
function controls(){
 const available={start:!!token,customer:!!token&&!pending(),blocked:!!customer&&!verified,verify:!!customer&&!verified,success:verified&&!pending(),failed:verified&&!pending(),timeout:verified&&!pending(),refresh:!!payment,replay:!!payment&&!pending(),callback:terminal(payment),isolation:!!payment,copy:!!token};
 for(const [id,on] of Object.entries(available)){const button=el(id);if(button)button.disabled=busy||!on;}
 const stage=!token?0:!customer?1:!verified?2:3;
 for(let i=0;i<4;i++){el('step-'+i).className=i<stage||i===3&&terminal(payment)?'done':i===stage?'current':'';if(i===stage&&!terminal(payment))el('step-'+i).setAttribute('aria-current','step');else el('step-'+i).removeAttribute('aria-current');}
 el('next').hidden=verified&&!pending()&&!busy;
 el('next').disabled=busy;
 el('scenario-hint').textContent=busy?'Processing your request — please wait…':pending()?'Your payment is still processing. Wait for its result before choosing another scenario.':verified?'Choose any scenario below. You can try all three, one after another.':'Complete the three setup steps above to unlock these scenarios.';
 el('next').textContent=busy?'Please wait…':pending()?'Check payment status →':['1. Start demo →','2. Create customer →','3. Verify identity →','Choose a scenario below'][stage];
}
async function api(path,method='GET',body,headers={},auth=token,expected=[]){
 const res=await fetch(path,{method,signal:AbortSignal.timeout(35000),headers:{...(auth?{Authorization:'Bearer '+auth}:{}),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined});
 let value;const text=await res.text();try{value=JSON.parse(text);}catch{value=text;}
 log(method+' '+path+' → HTTP '+res.status,value);
 if(!res.ok&&!expected.includes(res.status))throw new Error(res.status===401?'Your session expired. Start a new demo.':res.status===429?'Demo limit reached. Wait a minute or start a new session.':`Request failed (HTTP ${res.status}). Please try again.`);
 return {status:res.status,value};
}
function select(p){
 payment=p;el('payment').textContent='Payment '+p.id+' / '+p.status;el('payment-card').dataset.state=p.status;el('result-amount').textContent=money(p.amount);
 const states={CREATED:['Payment submitted','Your payment is safely recorded. Waiting for the payment worker.'],PROCESSING:['Processing your payment…','The payment provider is checking the request. This updates automatically.'],UNKNOWN:['Checking a delayed response…','The provider response timed out. We are checking the original payment to avoid charging twice.'],SUCCEEDED:['Payment successful ✓','The simulated payment completed. Your money has been recorded once.'],FAILED:['Payment declined','The simulator deliberately rejected this payment. No money was recorded.']};
 const [title,detail]=states[p.status]||['Checking payment…','Please wait.'];el('result-title').textContent=title;el('result-detail').textContent=detail;
 notice(title,detail,p.status==='SUCCEEDED'?'success':p.status==='FAILED'?'error':'pending');controls();
}
async function evidence(){
 const id=payment.id;const [ledger]=await Promise.all([api('/api/payments/'+id+'/ledger'),api('/api/payments/'+id+'/audit')]);
 if(payment.status==='SUCCEEDED'&&(ledger.value.length!==1||!ledger.value.every(row=>Number(row.debit_amount)===Number(row.credit_amount))))throw new Error('The ledger check did not pass. Inspect the technical response.');
 if(payment.status==='FAILED'&&ledger.value.length!==0)throw new Error('A declined payment unexpectedly has a ledger entry.');
 el('evidence-summary').textContent=payment.status==='SUCCEEDED'?`${ledger.value.length} ledger transaction · debit and credit balance · no duplicate payment.`:payment.status==='FAILED'?'0 ledger transactions · declined payments do not move money.':'Waiting for the final payment result…';
}
async function watch(){
 const deadline=Date.now()+60000;
 while(pending()&&Date.now()<deadline){await new Promise(resolve=>setTimeout(resolve,2000));const r=await api('/api/payments/'+payment.id);select(r.value);}
 if(terminal(payment))await evidence();
 else notice('Payment is still pending','The service may be waking up. Click “Check payment status” to continue.','pending');
}
function action(id,title,detail,fn){actions[id]=async()=>{
 if(busy)return;busy=true;notice(title,detail,'loading');controls();el(id)?.setAttribute('aria-busy','true');
 try{await fn();}catch(error){log('Request failed',{message:error.message});notice('We could not finish this step',error.message.includes('timeout')?'The request took too long. Please try again.':error.message,'error');}
 finally{busy=false;el(id)?.removeAttribute('aria-busy');controls();}
};el(id)?.addEventListener('click',actions[id]);}
el('next').addEventListener('click',()=>{if(busy)return;const id=!token?'start':!customer?'customer':!verified?'verify':pending()?'refresh':null;if(id)actions[id]();});
action('start','Starting your demo…','Creating your private 15-minute session. The first request can take longer.',async()=>{
 const r=await api('/api/demo/session','POST',undefined,{},null);token=r.value.access_token;customer=payment=lastRequest=lastKey=undefined;verified=false;for(const id of ['success','failed','timeout'])el(id).setAttribute('aria-pressed','false');
 el('session').textContent='Session active / '+r.value.merchant+' / expires in 15 minutes';el('payment').textContent='No payment selected.';el('payment-card').dataset.state='idle';el('result-title').textContent='Session ready ✓';el('result-detail').textContent='Next, create a simulated customer using the button above.';el('result-amount').textContent='€0.00';el('evidence-summary').textContent='The payment status will update automatically.';
 notice('Step 1 complete — session ready','Next: click “Create customer” above.','success');
});
action('customer','Creating your customer…','No personal information is needed. This customer is simulated.',async()=>{
 const r=await api('/api/customers','POST');customer=r.value;verified=false;payment=lastRequest=lastKey=undefined;el('payment-card').dataset.state='idle';el('result-amount').textContent='€0.00';el('payment').textContent='No payment selected.';el('evidence-summary').textContent='The payment status will update automatically.';notice('Step 2 complete — customer created','Next: verify this simulated identity to unlock payments.','success');el('result-title').textContent='Customer created ✓';el('result-detail').textContent='Identity is pending. Verify it to continue.';
});
action('blocked','Testing the identity check…','An unverified customer should be prevented from paying.',async()=>{
 const r=await api('/api/payments','POST',{customerId:customer.id,amount:10000,currency:'EUR'},{'Idempotency-Key':crypto.randomUUID()},token,[422]);
 if(r.status!==422)throw new Error('Unexpected result: an unverified customer was allowed to pay.');notice('Identity protection passed ✓','Payment blocked as expected. Verify the customer to continue.','success');
});
action('verify','Verifying the test identity…','This is a simulated identity decision.',async()=>{
 await api('/api/demo/customers/'+customer.id+'/verify','PUT');verified=true;el('result-title').textContent='Ready to pay ✓';el('result-detail').textContent='Identity verified. Choose a successful, declined or delayed payment from the three cards above.';notice('Step 3 complete — identity verified','Choose one of the three scenario cards. Each produces a different outcome.','success');
});
async function pay(amount){for(const [id,value] of [['success',10000],['failed',1300],['timeout',9900]])el(id).setAttribute('aria-pressed',String(value===amount));el('payment-card').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',block:'center'});lastRequest={customerId:customer.id,amount,currency:'EUR'};lastKey=crypto.randomUUID();el('evidence-summary').textContent='Waiting for the final payment result…';const r=await api('/api/payments','POST',lastRequest,{'Idempotency-Key':lastKey});select(r.value);await watch();}
action('success','Sending your €100 payment…','The provider will accept this payment. Watch the result update automatically.',()=>pay(10000));
action('failed','Testing a declined payment…','The €13 scenario deliberately simulates a provider rejection.',()=>pay(1300));
action('timeout','Testing a delayed response…','The €99 scenario demonstrates recovery without a duplicate charge.',()=>pay(9900));
action('refresh','Checking payment status…','Looking up the original payment and its ledger.',async()=>{const r=await api('/api/payments/'+payment.id);select(r.value);if(pending())await watch();else await evidence();});
action('replay','Repeating the same request…','The payment ID should stay the same.',async()=>{const old=payment.id;const r=await api('/api/payments','POST',lastRequest,{'Idempotency-Key':lastKey});if(r.value.id!==old)throw new Error('The repeated request returned a different payment.');select(r.value);notice('Duplicate request protection passed ✓','The same payment was returned. No second payment was created.','success');});
action('callback','Repeating settlement twice…','Checking that the ledger cannot record the payment twice.',async()=>{const r=await api('/api/demo/payments/'+payment.id+'/repeat-callback','POST');const expected=payment.status==='SUCCEEDED'?1:0;if(r.value.ledgerTransactions!==expected)throw new Error('Unexpected ledger count.');notice('Duplicate settlement protection passed ✓',`${r.value.ledgerTransactions} ledger transaction${expected===1?'':'s'} after repeated settlement. No duplicate money movement.`,'success');});
action('isolation','Checking merchant isolation…','A different merchant should not be able to see your payment.',async()=>{const other=await api('/api/demo/session','POST',undefined,{},null);const r=await api('/api/payments/'+payment.id,'GET',undefined,{},other.value.access_token,[404]);if(r.status!==404)throw new Error('Unexpected merchant access.');notice('Merchant isolation passed ✓','The other merchant could not access your payment.','success');});
action('copy','Copying your session token…','For manual API checks in Swagger.',async()=>{await navigator.clipboard.writeText(token);notice('Session token copied ✓','In Swagger choose Authorize and paste the token.','success');});
controls();
