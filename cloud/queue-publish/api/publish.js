import { send } from '@vercel/queue';
import { timingSafeEqual } from 'node:crypto';
const reply=(res,status,value)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value));};
export function createPublisher(sendMessage=send){return async function publish(req,res){
 if(req.method!=='POST')return reply(res,405,{error:'POST required'});
 const expected=process.env.CLOUD_INTERNAL_SECRET;
 const supplied=req.headers['x-internal-secret'];
 if(!expected||typeof supplied!=='string'||Buffer.byteLength(expected)!==Buffer.byteLength(supplied)||!timingSafeEqual(Buffer.from(expected),Buffer.from(supplied)))return reply(res,401,{error:'Unauthorized'});
 let body;
 try{
  if(req.body!==undefined)body=typeof req.body==='string'?JSON.parse(req.body):req.body;
  else{const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>4096)return reply(res,413,{error:'Body too large'});chunks.push(chunk);}body=JSON.parse(Buffer.concat(chunks).toString());}
 }catch{return reply(res,400,{error:'Invalid JSON'});}
 const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
 if(!body||!uuid.test(body.eventId)||!uuid.test(body.paymentId))return reply(res,400,{error:'Invalid event'});
 try{
  const result=await sendMessage('trustpay-payments',{eventId:body.eventId,paymentId:body.paymentId},{idempotencyKey:body.eventId,retentionSeconds:86400});
  return reply(res,200,{accepted:true,messageId:result.messageId});
 }catch(error){console.error('Queue publication failed',{name:error.name,code:error.code});return reply(res,503,{error:'Queue temporarily unavailable'});}
};}
export default createPublisher();
