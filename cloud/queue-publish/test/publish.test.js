import {createServer} from 'node:http';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createPublisher} from '../api/publish.js';
process.env.CLOUD_INTERNAL_SECRET='test-secret';
const event={eventId:'00000000-0000-4000-8000-000000000001',paymentId:'00000000-0000-4000-8000-000000000002'};
async function withServer(send,fn){const server=createServer(createPublisher(send));await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));try{await fn(`http://127.0.0.1:${server.address().port}`);}finally{await new Promise(resolve=>server.close(resolve));}}
test('raw Node request stream is parsed and accepted deferred queue response is acknowledged',async()=>{
 let sent;await withServer(async(...args)=>{sent=args;return{messageId:null};},async url=>{
 const r=await fetch(url,{method:'POST',headers:{'X-Internal-Secret':'test-secret','Content-Type':'application/json'},body:JSON.stringify(event)});
 assert.equal(r.status,200);assert.deepEqual(await r.json(),{accepted:true,messageId:null});assert.equal(sent[0],'trustpay-payments');assert.deepEqual(sent[1],event);assert.equal(sent[2].idempotencyKey,event.eventId);
 });
});
test('unauthenticated requests cannot publish',async()=>withServer(()=>{throw Error('must not publish');},async url=>{const r=await fetch(url,{method:'POST',body:JSON.stringify(event)});assert.equal(r.status,401);}));
test('malformed JSON returns 400 rather than invoking the queue',async()=>withServer(()=>{throw Error('must not publish');},async url=>{const r=await fetch(url,{method:'POST',headers:{'X-Internal-Secret':'test-secret'},body:'{'});assert.equal(r.status,400);}));
test('queue failure returns retryable 503',async()=>withServer(async()=>{throw new Error('offline');},async url=>{const r=await fetch(url,{method:'POST',headers:{'X-Internal-Secret':'test-secret'},body:JSON.stringify(event)});assert.equal(r.status,503);}));
