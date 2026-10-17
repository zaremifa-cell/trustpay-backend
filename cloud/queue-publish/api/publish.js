import { send } from '@vercel/queue';
import { timingSafeEqual } from 'node:crypto';
export default async function publish(req,res){
 if(req.method!=='POST')return res.status(405).json({error:'POST required'});
 const expected=process.env.CLOUD_INTERNAL_SECRET;
 const supplied=req.headers['x-internal-secret'];
 if(!expected||typeof supplied!=='string'||Buffer.byteLength(expected)!==Buffer.byteLength(supplied)||!timingSafeEqual(Buffer.from(expected),Buffer.from(supplied)))return res.status(401).json({error:'Unauthorized'});
 const body=typeof req.body==='string'?JSON.parse(req.body):req.body;
 const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
 if(!body||!uuid.test(body.eventId)||!uuid.test(body.paymentId))return res.status(400).json({error:'Invalid event'});
 const result=await send('trustpay-payments',{eventId:body.eventId,paymentId:body.paymentId},{idempotencyKey:body.eventId,retentionSeconds:86400});
 res.status(200).json({accepted:true,messageId:result.messageId});
}
