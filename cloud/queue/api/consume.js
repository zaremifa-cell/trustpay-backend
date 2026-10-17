import { QueueClient } from '@vercel/queue';
const queue=new QueueClient();
export default queue.handleNodeCallback(async(message,metadata)=>{
 const response=await fetch(new URL('/api/internal/cloud/consume',process.env.JAVA_INTERNAL_URL),{method:'POST',headers:{'Content-Type':'application/json','X-Internal-Secret':process.env.CLOUD_INTERNAL_SECRET},body:JSON.stringify(message),signal:AbortSignal.timeout(60000)});
 if(!response.ok)throw new Error(`Java payment worker returned ${response.status}`);
 console.log('Payment delivery completed',{messageId:metadata.messageId,deliveryCount:metadata.deliveryCount});
},{retry:(error,metadata)=>metadata.deliveryCount>=8?{acknowledge:true}:{afterSeconds:Math.min(60,2**metadata.deliveryCount)}});
