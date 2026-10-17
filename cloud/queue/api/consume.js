import { QueueClient } from '@vercel/queue';
const queue=new QueueClient();
const consume=queue.handleCallback(async(message,metadata)=>{
 const response=await fetch(new URL('/api/internal/cloud/consume',process.env.JAVA_INTERNAL_URL),{method:'POST',headers:{'Content-Type':'application/json','X-Internal-Secret':process.env.CLOUD_INTERNAL_SECRET},body:JSON.stringify(message),signal:AbortSignal.timeout(60000)});
 if(!response.ok)throw new Error(`Java payment worker returned ${response.status}`);
 console.log('Payment delivery completed',{messageId:metadata.messageId,deliveryCount:metadata.deliveryCount});
},{retry:(error,metadata)=>metadata.deliveryCount>=8?{acknowledge:true}:{afterSeconds:Math.min(60,2**metadata.deliveryCount)}});

// The native Node service exposes IncomingMessage/ServerResponse, without Express helpers.
export function nodeCallback(webHandler){return async(req,res)=>{
 const chunks=[];let size=0;
 for await(const chunk of req){size+=chunk.length;if(size>65536){res.writeHead(413);res.end();return;}chunks.push(chunk);}
 const headers=new Headers();for(const [key,value] of Object.entries(req.headers))if(value!==undefined)headers.set(key,Array.isArray(value)?value.join(','):value);
 const body=Buffer.concat(chunks);
 const request=new Request('https://queue.internal'+(req.url||'/'),{method:req.method,headers,...(!['GET','HEAD'].includes(req.method)?{body}:{} )});
 const response=await webHandler(request);
 res.writeHead(response.status,Object.fromEntries(response.headers));res.end(Buffer.from(await response.arrayBuffer()));
};}
export default nodeCallback(consume);
