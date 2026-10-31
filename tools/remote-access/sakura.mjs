// Request fields follow https://github.com/natfrp/api (OpenAPI v4).
export async function sakuraRequest(token,route,{method='GET',form,fetchImpl=fetch}={}){
  if(!/^\/[a-z0-9/_-]+$/.test(route))throw Error('Invalid Sakura API route');
  const response=await fetchImpl(`https://api.natfrp.com/v4${route}`,{
    method,headers:{Authorization:`Bearer ${token}`,...(form?{'Content-Type':'application/x-www-form-urlencoded'}:{})},
    body:form?new URLSearchParams(form):undefined,signal:AbortSignal.timeout(20000),redirect:'error'
  });
  // Provider errors can contain credentials or submitted private configuration.
  if(!response.ok){await response.body?.cancel();throw Error(`Sakura API ${route}: HTTP ${response.status}`);}
  if(response.status===204)return null;
  const value=await response.json();return value;
}
export function freeMainlandNodes(nodes){
  return Object.entries(nodes).filter(([,n])=>n.vip===0&&(n.flag&4)&&(n.flag&8)&&!(n.flag&512)&&!(n.flag&256))
    .map(([id,n])=>({id:Number(id),...n}));
}
