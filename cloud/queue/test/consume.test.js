import {createServer} from 'node:http';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {nodeCallback} from '../api/consume.js';
test('native HTTP adapter preserves callback bytes, CloudEvent headers and SDK response',async()=>{
 let request;const server=createServer(nodeCallback(async r=>{request={body:await r.text(),event:r.headers.get('ce-id')};return new Response('accepted',{status:202,headers:{'x-receipt':'ok'}});}));
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));try{
 const response=await fetch(`http://127.0.0.1:${server.address().port}/index`,{method:'POST',headers:{'ce-id':'event-1','Content-Type':'application/json'},body:'{"paymentId":"123"}'});
 assert.equal(response.status,202);assert.equal(response.headers.get('x-receipt'),'ok');assert.equal(await response.text(),'accepted');assert.deepEqual(request,{body:'{"paymentId":"123"}',event:'event-1'});
 }finally{await new Promise(resolve=>server.close(resolve));}
});
