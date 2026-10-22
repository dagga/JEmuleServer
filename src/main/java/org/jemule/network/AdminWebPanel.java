/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jemule.Main;
import org.jemule.config.ConfigApplyResult;
import org.jemule.config.ConfigParameter;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileSource;
import org.jemule.core.MetricsHistory;
import org.jemule.kad.GlobalSearchForwarder;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadSearchBridge;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.IPFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Loopback-only HTTP dashboard and JSON API for local server administration.
 */
final class AdminWebPanel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AdminWebPanel.class);
    private static final int MAX_BODY_BYTES = 4096;
    private static final Pattern HASH = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final String PAGE = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>JEmuleServer admin</title>
            <style>
            :root{color-scheme:dark;--page:#0d0d0d;--surface:#1a1a19;--raised:#252523;--ink:#f0efec;--ink2:#c3c2b7;--muted:#898781;
            --grid:#2c2c2a;--axis:#383835;--border:rgba(255,255,255,.10);--accent:#3987e5;--s1:#3987e5;--s2:#d95926;--track:#184f95;
            --goodText:#0ca30c;--good:#0ca30c;--warning:#fab219;--critical:#d03b3b}
            @media (prefers-color-scheme: light){:root{color-scheme:light;--page:#f9f9f7;--surface:#fcfcfb;--raised:#f1f0ec;--ink:#0b0b0b;
            --ink2:#52514e;--grid:#e1e0d9;--axis:#c3c2b7;--border:rgba(11,11,11,.10);--accent:#2a78d6;--s1:#2a78d6;--s2:#eb6834;--track:#cde2fb;
            --goodText:#006300}}
            *{box-sizing:border-box}
            body{margin:0;font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;background:var(--page);color:var(--ink)}
            .wrap{max-width:1180px;margin:0 auto;padding:20px 16px 48px}
            header{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap}
            h1{font-size:20px;margin:0;font-weight:650}
            .meta,.note,.sub{color:var(--muted);font-size:13px}
            .note{margin:4px 0 0}
            nav{display:flex;gap:2px;flex-wrap:wrap;border-bottom:1px solid var(--border);margin:14px 0 16px}
            nav button{background:none;border:0;border-bottom:2px solid transparent;color:var(--ink2);padding:9px 12px;font:inherit;cursor:pointer}
            nav button:hover{color:var(--ink)}
            nav button[aria-selected=true]{color:var(--ink);border-bottom-color:var(--accent);font-weight:600}
            .card{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:16px;min-width:0}
            .grid{display:grid;gap:12px}
            .hero{grid-template-columns:minmax(0,1.3fr) minmax(0,1fr);margin-bottom:12px}
            .kpis{grid-template-columns:repeat(auto-fill,minmax(250px,1fr))}
            @media (max-width:760px){.hero{grid-template-columns:1fr}}
            .label{color:var(--ink2);font-size:13px}
            .value{font-size:26px;font-weight:600;margin:4px 0 2px}
            .big{font-size:52px;font-weight:650;line-height:1.1;margin:6px 0 8px}
            .deltas{display:flex;gap:16px;flex-wrap:wrap;font-size:14px}
            .up{color:var(--goodText)}.flat,.down{color:var(--ink2)}
            .meter{height:8px;border-radius:4px;background:var(--track);overflow:hidden;margin:10px 0 8px}
            .meter>i{display:block;height:100%;border-radius:4px}
            .state{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--ink2)}
            .state svg{flex:none}
            .charts{margin-top:12px}
            .range{display:flex;align-items:center;gap:10px;margin:18px 0 0;flex-wrap:wrap}
            .seg{display:inline-flex;border:1px solid var(--border);border-radius:8px;overflow:hidden}
            .seg button{background:none;border:0;color:var(--ink2);padding:6px 12px;font:inherit;font-size:13px;cursor:pointer}
            .seg button[aria-pressed=true]{background:var(--raised);color:var(--ink);font-weight:600}
            .chart-title{font-size:15px;font-weight:600;margin:0 0 2px}
            .plot{position:relative;margin-top:8px}
            .plot svg{display:block;width:100%;height:240px;overflow:visible}
            .plot svg:focus{outline:2px solid var(--accent);outline-offset:2px}
            .tip{position:absolute;top:0;pointer-events:none;background:var(--raised);border:1px solid var(--border);border-radius:8px;
            padding:6px 10px;font-size:12px;white-space:nowrap;box-shadow:0 6px 18px rgba(0,0,0,.25);display:none;color:var(--ink2)}
            .tip strong{display:block;font-size:15px;color:var(--ink);font-weight:600}
            .empty{color:var(--muted);padding:40px 0;text-align:center}
            details{margin-top:10px}summary{cursor:pointer;color:var(--ink2);font-size:13px}
            .scroll{overflow-x:auto;max-height:420px;overflow-y:auto}
            table{border-collapse:collapse;width:100%;font-size:13px}
            th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--grid);overflow-wrap:anywhere;vertical-align:top}
            thead th{color:var(--ink2);font-weight:600;position:sticky;top:0;background:var(--surface)}
            tbody tr:hover{background:var(--raised)}
            .num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
            .toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
            .toolbar:empty{display:none}
            input{background:var(--page);color:var(--ink);border:1px solid var(--border);border-radius:7px;padding:7px 9px;font:inherit}
            button.btn{background:var(--raised);color:var(--ink);border:1px solid var(--border);border-radius:7px;padding:7px 11px;font:inherit;cursor:pointer}
            button.btn:hover{border-color:var(--accent)}
            #notice{color:var(--ink2);min-height:1.2em;margin:8px 0}
            </style>
            <body><div class="wrap">
            <header><h1>JEmuleServer admin</h1><div class="meta" id="meta"></div></header>
            <p class="note">Administration only. Potential bots are identified by nickname heuristic, not verified.</p>
            <nav role="tablist"><button role="tab" data-view="overview">Overview</button><button role="tab" data-view="status">Status</button>
            <button role="tab" data-view="clients">Clients</button><button role="tab" data-view="peers">Peers</button>
            <button role="tab" data-view="servers">Servers</button><button role="tab" data-view="ingestion">Ingestion</button>
            <button role="tab" data-view="searches">Searches</button><button role="tab" data-view="filters">Filters</button>
            <button role="tab" data-view="bots">Bots</button><button role="tab" data-view="blocks">Blocks</button>
            <button role="tab" data-view="settings">Settings</button></nav>
            <main><div class="toolbar" id="controls"></div><p id="notice"></p><div id="content">Loading…</div></main>
            </div>
            <script>
            const $=s=>document.querySelector(s);
            const content=$('#content'),controls=$('#controls'),notice=$('#notice'),meta=$('#meta');
            const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
            const NF=new Intl.NumberFormat('en'),CF=new Intl.NumberFormat('en',{notation:'compact',maximumFractionDigits:1});
            const fmt=n=>n==null?'–':NF.format(n),compact=n=>n==null?'–':CF.format(n);
            const DAY=86400000;
            let timers=[],range=30,hist=null;
            function stopTimers(){timers.forEach(clearInterval);timers=[]}
            async function getJson(path){const r=await fetch(path);const d=await r.json();if(!r.ok)throw Error(d.error||r.statusText);return d}
            async function post(path,data){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});const x=await r.json();if(!r.ok)throw Error(x.error||(x.errors&&x.errors.join('; '))||r.statusText);return x}
            function summarize(r){const parts=[];if(r.applied&&r.applied.length)parts.push('applied: '+r.applied.join(', '));if(r.deferred&&r.deferred.length)parts.push('restart required: '+r.deferred.join(', '));if(r.errors&&r.errors.length)parts.push('errors: '+r.errors.join('; '));return parts.join(' | ')||'No change.'}
            function duration(s){const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return d?d+' d '+h+' h':h?h+' h '+m+' min':m+' min'}
            function clock(t){return new Date(t).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'})}
            function day(t){return new Date(t).toLocaleDateString('en-GB',{day:'numeric',month:'short'})}
            function stamp(t){return day(t)+', '+clock(t)}

            /* Status: an icon and a label, never color alone */
            const STATES={good:['Normal','var(--good)','M3 7l3 3 5-6'],warning:['High','var(--warning)','M7 3v5M7 10.5v.5'],critical:['Critical','var(--critical)','M4 4l6 6M10 4l-6 6']};
            function state(level,label){const[def,color,path]=STATES[level];
              return '<span class="state"><svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="7" fill="'+color+'"/><path d="'+path+'" stroke="#0b0b0b" stroke-width="1.8" fill="none" stroke-linecap="round"/></svg>'+esc(label||def)+'</span>'}
            function level(ratio){return ratio>=.9?'critical':ratio>=.75?'warning':'good'}
            function meter(ratio){const l=level(ratio);const color=l==='good'?'var(--accent)':'var(--'+l+')';
              return '<div class="meter" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+Math.round(ratio*100)+'"><i style="width:'+Math.min(100,ratio*100).toFixed(1)+'%;background:'+color+'"></i></div>'}
            function delta(now,before,span){if(before==null)return '<span class="flat">'+esc(span)+': no sample yet</span>';
              const d=now-before;const cls=d>0?'up':d<0?'down':'flat';const sign=d>0?'▲ +':d<0?'▼ ':'';
              return '<span class="'+cls+'">'+sign+fmt(d)+'</span> <span class="sub">'+esc(span)+'</span>'}
            function tile(label,value,sub,extra){return '<div class="card"><div class="label">'+esc(label)+'</div><div class="value">'+value+'</div>'+(extra||'')+'<div class="sub">'+sub+'</div></div>'}

            function show(view){
              stopTimers();notice.textContent='';controls.replaceChildren();
              document.querySelectorAll('[data-view]').forEach(b=>b.setAttribute('aria-selected',String(b.dataset.view===view)));
              if(view==='overview'){overview();return}
              if(view==='clients'){controls.innerHTML='<input id="query" placeholder="Search nickname, IP, or client ID"><button class="btn" id="search">Search</button>';
                $('#search').onclick=()=>load(view,$('#query').value);}
              else if(view==='blocks'){controls.innerHTML='<input id="hash" placeholder="32-character MD4 hash"><input id="reason" placeholder="Reason"><button class="btn" id="ban">Ban hash</button><button class="btn" id="unban">Unban hash</button>';
                $('#ban').onclick=async()=>{try{await post('/api/blocks',{hash:$('#hash').value,reason:$('#reason').value});notice.textContent='Hash banned.';load(view)}catch(e){notice.textContent=e.message}};
                $('#unban').onclick=async()=>{try{await post('/api/unblock',{hash:$('#hash').value});notice.textContent='Hash unbanned.';load(view)}catch(e){notice.textContent=e.message}}}
              else if(view==='settings'){controls.innerHTML='<button class="btn" id="reload">Reload from file</button><button class="btn" id="save">Save changes</button>';
                $('#reload').onclick=async()=>{try{const r=await post('/api/settings/reload',{});notice.textContent=summarize(r);load(view)}catch(e){notice.textContent=e.message}};
                $('#save').onclick=()=>saveSettings();}
              else if(view==='filters'){controls.innerHTML='<button class="btn" id="reloadIp">Reload IP filter</button>';
                $('#reloadIp').onclick=async()=>{try{const r=await post('/api/filters/reload',{});notice.textContent=(r.reloaded?'IP filter reload requested.':'IP filter path is empty.')+' Child abuse keywords reloaded.';load(view)}catch(e){notice.textContent=e.message}}}
              load(view);
            }

            /* Overview: headline figures refreshed every 30 s, curves every 5 minutes (the sampling interval) */
            async function overview(){
              content.innerHTML='<div class="grid hero"><div class="card" id="heroFiles"></div><div class="card" id="heroCapacity"></div></div>'+
                '<div class="grid kpis" id="kpis"></div>'+
                '<div class="range"><span class="label">Range</span><div class="seg" id="range">'+[[1,'24 h'],[7,'7 days'],[30,'30 days']].map(([d,l])=>'<button data-days="'+d+'" aria-pressed="'+(d===range)+'">'+l+'</button>').join('')+'</div><span class="sub" id="histNote"></span></div>'+
                '<div class="grid charts">'+chartCard('files','Indexed files','Files known to the index')+chartCard('clients','Connected clients','eD2K clients connected to this server')+'</div>';
              $('#range').onclick=e=>{const b=e.target.closest('button');if(!b)return;range=+b.dataset.days;
                document.querySelectorAll('#range button').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));drawCharts()};
              await Promise.all([refreshKpis(),refreshHistory()]);
              timers.push(setInterval(refreshKpis,30000),setInterval(refreshHistory,300000));
            }
            function chartCard(key,title,desc){return '<div class="card"><p class="chart-title">'+title+'</p><div class="sub">'+desc+', sampled every 5 minutes</div>'+
              '<div class="plot" id="plot-'+key+'"></div><details><summary>Show as table</summary><div class="scroll" id="table-'+key+'"></div></details></div>'}
            async function refreshKpis(){
              let o;try{o=await getJson('/api/overview')}catch(e){notice.textContent='Request failed: '+e.message;return}
              notice.textContent='';
              meta.textContent='v'+o.version+' · up '+duration(o.uptimeSeconds)+' · updated '+clock(Date.now());
              const f=o.files,c=o.clients,m=o.memory,s=o.searches;
              $('#heroFiles').innerHTML='<div class="label">Indexed files</div><div class="big">'+fmt(f.indexed)+'</div><div class="deltas">'+
                delta(f.indexed,f.ago24h,'in 24 h')+delta(f.indexed,f.ago7d,'in 7 days')+'</div><div class="sub" style="margin-top:6px">'+fmt(f.newSinceStart)+' new files since the last start</div>';
              const fill=f.max?f.indexed/f.max:0;
              $('#heroCapacity').innerHTML='<div class="label">Index capacity</div><div class="value">'+(fill*100).toFixed(1)+' %</div>'+meter(fill)+
                state(level(fill),fill>=.9?'Near the limit':fill>=.75?'Filling up':'Room to grow')+'<div class="sub" style="margin-top:6px">'+fmt(f.indexed)+' of '+fmt(f.max)+' files (maxFiles); new files are refused beyond, known files still get sources</div>';
              const heap=m.heapMaxMb?m.heapUsedMb/m.heapMaxMb:0,lowShare=c.connected?Math.round(c.lowId*100/c.connected):0;
              $('#kpis').innerHTML=
                tile('Connected clients',fmt(c.connected),fmt(c.lowId)+' LowID ('+lowShare+' %) · max '+fmt(c.max))+
                tile('Heap',fmt(m.heapUsedMb)+' MB','of '+fmt(m.heapMaxMb)+' MB, including uncollected garbage'+(m.systemLoad!=null?' · load '+m.systemLoad.toFixed(2):''),meter(heap))+
                tile('Peer eD2K servers',fmt(o.servers.verified),'verified of '+fmt(o.servers.known)+' known')+
                tile('Remote searches',fmt(s.kad+s.ed2kServers),'Kad '+fmt(s.kad)+' · eD2K servers '+fmt(s.ed2kServers)+' · UDP global received '+fmt(s.udpGlobal))+
                tile('Files found by searches',fmt(s.filesIngested),'indexed since the last start')+
                tile('Banned hashes',compact(o.protection.bannedHashes),fmt(o.protection.bannedHashes)+' hashes · '+fmt(o.protection.ipFilterRanges)+' IP filter ranges')+
                tile('Database',o.database.sizeMb==null?'–':fmt(o.database.sizeMb)+' MB',o.database.reopens?state('critical','Reopened '+o.database.reopens+' times after a fatal error'):state('good','No fatal error'))+
                tile('Uptime',duration(o.uptimeSeconds),'version '+esc(o.version));
            }
            async function refreshHistory(){try{hist=await getJson('/api/history?days=30')}catch(e){notice.textContent='Request failed: '+e.message;return}drawCharts()}
            function drawCharts(){
              if(!hist||!$('#plot-files'))return;
              const from=Date.now()-range*DAY,pts=hist.points.filter(p=>p[0]>=from);
              const all=hist.points;
              $('#histNote').textContent=all.length?fmt(all.length)+' samples since '+stamp(all[0][0]):'';
              lineChart($('#plot-files'),pts,1,'var(--s1)','Indexed files',hist.intervalSeconds*1000);
              lineChart($('#plot-clients'),pts,2,'var(--s2)','Connected clients',hist.intervalSeconds*1000);
              dailyTable($('#table-files'),pts,1,'Indexed files');dailyTable($('#table-clients'),pts,2,'Connected clients');
            }
            function dailyTable(el,pts,k,name){const byDay=new Map();pts.forEach(p=>byDay.set(day(p[0]),p));
              const rows=[...byDay.entries()].reverse();
              el.innerHTML=rows.length?'<table><thead><tr><th>Day</th><th>Last sample</th><th class="num">'+esc(name)+'</th></tr></thead><tbody>'+
                rows.map(([d,p])=>'<tr><td>'+esc(d)+'</td><td>'+esc(clock(p[0]))+'</td><td class="num">'+fmt(p[k])+'</td></tr>').join('')+'</tbody></table>':'<p class="sub">No sample yet.</p>'}
            function ticks(lo,hi,n){if(lo===hi){lo-=1;hi+=1}const raw=(hi-lo)/n,mag=10**Math.floor(Math.log10(raw)),e=raw/mag;
              const step=(e>=7.5?10:e>=3.5?5:e>=1.5?2:1)*mag,a=Math.floor(lo/step)*step,b=Math.ceil(hi/step)*step,t=[];
              for(let v=a;v<=b+step/2;v+=step)t.push(Math.round(v*1e6)/1e6);return{a,b,t}}
            function timeTicks(t0,t1){const out=[];const span=t1-t0;
              if(span<=2*DAY){const step=(span<=12*3600000?2:span<=DAY*1.1?3:6)*3600000;let t=Math.ceil(t0/step)*step;for(;t<=t1;t+=step)out.push([t,clock(t)]);return out}
              const stepDays=span<=8*DAY?1:span<=16*DAY?2:5;const d=new Date(t0);d.setHours(24,0,0,0);
              for(let t=d.getTime(),i=0;t<=t1;t+=DAY,i++)if(i%stepDays===0)out.push([t,day(t)]);return out}
            /* More samples than pixels: one point per bucket of about 2 px, the average of its samples */
            function buckets(pts,k,interval,width){
              const span=pts[pts.length-1][0]-pts[0][0],size=Math.max(interval,span/(width/2));
              const out=[];let cur=null,prev=null;
              pts.forEach(p=>{const gap=prev!==null&&p[0]-prev>3*interval;
                if(!cur||gap||p[0]-cur.a>=size){cur={a:p[0],b:p[0],sum:0,n:0,gap};out.push(cur)}
                cur.b=p[0];cur.sum+=p[k];cur.n++;prev=p[0]});
              return out.map(c=>({t:c.n>1?(c.a+c.b)/2:c.a,v:c.sum/c.n,n:c.n,a:c.a,b:c.b,gap:c.gap}))}
            function lineChart(el,pts,k,color,name,interval){
              if(pts.length<2){el.innerHTML='<div class="empty">'+(pts.length?'One sample so far: '+fmt(pts[0][k])+'. ':'')+'The curve appears after the next samples (every 5 minutes).</div>';return}
              const W=Math.max(320,el.clientWidth),H=240,L=64,R=16,T=12,B=28,iw=W-L-R,ih=H-T-B;
              const raw=pts,t0=raw[0][0],t1=raw[raw.length-1][0];
              pts=buckets(raw,k,interval,iw);let lo=Infinity,hi=-Infinity;pts.forEach(p=>{lo=Math.min(lo,p.v);hi=Math.max(hi,p.v)});
              const pad=(hi-lo)*.08||1,yt=ticks(Math.max(0,lo-pad),hi+pad,4);
              const x=t=>L+(t-t0)/((t1-t0)||1)*iw,y=v=>T+ih-(v-yt.a)/((yt.b-yt.a)||1)*ih;
              let d='';pts.forEach((p,i)=>{d+=(i===0||p.gap?'M':'L')+x(p.t).toFixed(1)+' '+y(p.v).toFixed(1)});
              const last=pts[pts.length-1],lastRaw=raw[raw.length-1];
              let s='<svg viewBox="0 0 '+W+' '+H+'" tabindex="0" role="img" aria-label="'+esc(name)+': '+fmt(lastRaw[k])+' at '+esc(stamp(lastRaw[0]))+'. Use the arrow keys to read the samples.">';
              yt.t.forEach(v=>{const yy=y(v).toFixed(1);s+='<line x1="'+L+'" x2="'+(W-R)+'" y1="'+yy+'" y2="'+yy+'" style="stroke:var(--grid)"/><text x="'+(L-8)+'" y="'+yy+'" dy="4" text-anchor="end" style="fill:var(--muted);font-size:11px;font-variant-numeric:tabular-nums">'+compact(v)+'</text>'});
              s+='<line x1="'+L+'" x2="'+(W-R)+'" y1="'+(T+ih)+'" y2="'+(T+ih)+'" style="stroke:var(--axis)"/>';
              timeTicks(t0,t1).forEach(([t,l])=>{s+='<text x="'+x(t).toFixed(1)+'" y="'+(H-8)+'" text-anchor="middle" style="fill:var(--muted);font-size:11px">'+esc(l)+'</text>'});
              s+='<path d="'+d+'" fill="none" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" style="stroke:'+color+'"/>';
              s+='<circle cx="'+x(last.t).toFixed(1)+'" cy="'+y(last.v).toFixed(1)+'" r="4" stroke-width="2" style="fill:'+color+';stroke:var(--surface)"/>';
              s+='<line class="cross" y1="'+T+'" y2="'+(T+ih)+'" style="stroke:var(--ink2);display:none"/><circle class="dot" r="4.5" stroke-width="2" style="fill:'+color+';stroke:var(--surface);display:none"/>';
              s+='<rect x="'+L+'" y="'+T+'" width="'+iw+'" height="'+ih+'" fill="transparent"/></svg><div class="tip"></div>';
              el.innerHTML=s;
              const svg=el.querySelector('svg'),cross=svg.querySelector('.cross'),dot=svg.querySelector('.dot'),tip=el.querySelector('.tip');
              let cur=pts.length-1;
              function at(i){cur=Math.max(0,Math.min(pts.length-1,i));const p=pts[cur],px=x(p.t),py=y(p.v),scale=svg.getBoundingClientRect().width/W;
                cross.setAttribute('x1',px);cross.setAttribute('x2',px);cross.style.display='';dot.setAttribute('cx',px);dot.setAttribute('cy',py);dot.style.display='';
                tip.replaceChildren();const v=document.createElement('strong');v.textContent=fmt(Math.round(p.v));
                tip.append(v,document.createTextNode(p.n>1?name+' · average of '+p.n+' samples · '+stamp(p.a)+' to '+clock(p.b):name+' · '+stamp(p.a)));
                tip.style.display='block';const left=px*scale+12,w=tip.offsetWidth;tip.style.left=(left+w>el.clientWidth?px*scale-w-12:left)+'px';tip.style.top=Math.max(0,py*scale-48)+'px'}
              function hide(){cross.style.display='none';dot.style.display='none';tip.style.display='none'}
              function nearest(t){let a=0,b=pts.length-1;while(b-a>1){const m=(a+b)>>1;if(pts[m].t<t)a=m;else b=m}return t-pts[a].t<pts[b].t-t?a:b}
              svg.addEventListener('pointermove',e=>{const r=svg.getBoundingClientRect(),vx=(e.clientX-r.left)*W/r.width;at(nearest(t0+(vx-L)/iw*(t1-t0)))});
              svg.addEventListener('pointerleave',hide);svg.addEventListener('blur',hide);svg.addEventListener('focus',()=>at(cur));
              svg.addEventListener('keydown',e=>{if(e.key==='ArrowLeft'){at(cur-1);e.preventDefault()}else if(e.key==='ArrowRight'){at(cur+1);e.preventDefault()}else if(e.key==='Home'){at(0)}else if(e.key==='End'){at(pts.length-1)}});
            }
            let resizeTimer;window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(drawCharts,150)});

            /* The other views: tables from the JSON API */
            async function load(view,q=''){content.textContent='Loading…';try{
              const data=await getJson('/api/'+view+(view==='clients'&&q?'?q='+encodeURIComponent(q):''));
              if(view==='status'||view==='filters'){content.innerHTML='<div class="card"><table><tbody>'+Object.entries(data).map(([k,v])=>'<tr><th>'+esc(k)+'</th><td class="'+(typeof v==='number'?'num':'')+'">'+esc(typeof v==='number'?fmt(v):v)+'</td></tr>').join('')+'</tbody></table></div>';return}
              if(view==='settings'){renderSettings(data);return}
              const rows=Array.isArray(data)?data:(data.clients||[]);
              if(view==='clients')notice.textContent='Matching clients: '+data.total+' (showing '+rows.length+'; limit '+data.limit+')';
              if(view==='bots')notice.textContent='Potential bots matched by nickname only: '+rows.length;
              if(!rows.length){content.innerHTML='<div class="card empty">No entries.</div>';return}
              const keys=Object.keys(rows[0]),numeric=keys.map(k=>rows.every(r=>r[k]==null||typeof r[k]==='number'));
              content.innerHTML='<div class="card scroll"><table><thead><tr>'+keys.map((k,i)=>'<th class="'+(numeric[i]?'num':'')+'">'+esc(k)+'</th>').join('')+'<th></th></tr></thead><tbody>'+
                rows.map(row=>'<tr>'+keys.map((k,i)=>'<td class="'+(numeric[i]?'num':'')+'">'+esc(numeric[i]&&row[k]!=null&&!/port|Id|Port/.test(k)?fmt(row[k]):row[k])+'</td>').join('')+
                '<td>'+(row.clientId?'<button class="btn" data-kick="'+esc(row.clientId)+'">Kick</button>':'')+'</td></tr>').join('')+'</tbody></table></div>';
              content.querySelectorAll('[data-kick]').forEach(b=>b.onclick=async()=>{try{await post('/api/kick',{clientId:b.dataset.kick});notice.textContent='Client kicked.';load(view,q)}catch(e){notice.textContent=e.message}});
            }catch(e){content.textContent='Request failed: '+e.message}}
            function renderSettings(list){
              content.innerHTML='<div class="card scroll"><table><thead><tr><th>Key</th><th>Value</th><th>Scope</th><th>Description</th></tr></thead><tbody>'+
                list.map(p=>'<tr><th>'+esc(p.key)+'</th><td>'+field(p)+(p.pending!==''?'<br><small>After restart: '+esc(p.pending)+'</small>':'')+'</td><td>'+esc(p.scope)+'</td><td>'+esc(p.description)+'</td></tr>').join('')+'</tbody></table></div>';
            }
            function field(p){const id='set_'+p.key,orig=esc(String(p.value));
              if(p.type==='BOOLEAN')return '<input type="checkbox" id="'+id+'" data-orig="'+orig+'" '+(p.value?'checked':'')+'>';
              return '<input id="'+id+'" data-orig="'+orig+'" value="'+orig+'">';}
            async function saveSettings(){const data={};
              document.querySelectorAll('#content input[id^=set_]').forEach(el=>{const key=el.id.slice(4);const value=el.type==='checkbox'?String(el.checked):el.value;if(value!==el.dataset.orig)data[key]=value});
              if(!Object.keys(data).length){notice.textContent='No change.';return}
              try{const r=await post('/api/settings',data);notice.textContent=summarize(r);if(r.errors&&r.errors.length)return;load('settings')}catch(e){notice.textContent=e.message}}
            document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>show(b.dataset.view));show('overview');
            </script></body></html>
            """;

    private final Server server;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer http;
    private ExecutorService executor;

    AdminWebPanel(Server server) {
        this.server = server;
    }

    synchronized void start(int port) throws IOException {
        if (http != null) return;
        String bindConfig = (server != null && server.getConfig() != null)
                ? server.getConfig().adminBindAddress()
                : ServerConfig.DEFAULT_ADMIN_BIND_ADDRESS;
        InetAddress bindAddr;
        try {
            bindAddr = InetAddress.getByName(bindConfig);
        } catch (Exception e) {
            log.error("Invalid adminBindAddress {}: {}", bindConfig, e.getMessage());
            bindAddr = InetAddress.getByName("127.0.0.1");
        }
        http = HttpServer.create(new InetSocketAddress(bindAddr, port), 32);
        if (executor == null) executor = org.jemule.core.ServerThreads.adminExecutor("admin-web");
        http.setExecutor(executor);
        http.createContext("/", this::handle);
        http.start();
        log.info("Admin web panel listening at http://{}:{}/", bindAddr.getHostAddress(), http.getAddress().getPort());
    }

    int port() {
        return http == null ? 0 : http.getAddress().getPort();
    }

    boolean isLoopbackBound() {
        return http != null && http.getAddress().getAddress().isLoopbackAddress();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage());
            } catch (IOException e) {
                log.warn("Admin web request failed: {}", e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                log.error("Admin web request failed", e);
                if (exchange.getResponseCode() == -1) sendError(exchange, 500, "Internal server error");
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
        if (server.getAdminAccessFilter() != null && !server.getAdminAccessFilter().isAllowed(remoteAddress)) {
            sendError(exchange, 403, "Access denied by admin access filter");
            return;
        }
        if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            sendError(exchange, 403, "Valid Host header required");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/") && method.equals("GET")) {
            send(exchange, 200, "text/html; charset=utf-8", PAGE);
            return;
        }
        if (method.equals("GET")) {
            switch (path) {
                case "/api/status" -> json(exchange, 200, status());
                case "/api/overview" -> json(exchange, 200, overview());
                case "/api/history" -> json(exchange, 200, history(exchange));
                case "/api/clients" -> clients(exchange);
                case "/api/peers" -> json(exchange, 200, peers());
                case "/api/servers" -> json(exchange, 200, servers());
                case "/api/ingestion" -> json(exchange, 200, ingestion());
                case "/api/searches" -> json(exchange, 200, searches());
                case "/api/filters" -> json(exchange, 200, filters());
                case "/api/bots" -> json(exchange, 200, bots());
                case "/api/blocks" -> json(exchange, 200, blocks());
                case "/api/settings" -> json(exchange, 200, settings());
                default -> sendError(exchange, 404, "Not found");
            }
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"), port())) {
            sendError(exchange, 403, "A valid Origin header is required");
            return;
        }
        Map<String, String> form = readForm(exchange);
        switch (path) {
            case "/api/blocks" -> {
                String hash = form.getOrDefault("hash", "").trim();
                if (!HASH.matcher(hash).matches())
                    throw new IllegalArgumentException("Hash must contain 32 hexadecimal characters");
                String reason = form.getOrDefault("reason", "Admin web panel").trim();
                if (reason.length() > 240) throw new IllegalArgumentException("Reason is too long");
                String normalized = hash.toUpperCase(Locale.ROOT);
                server.getFakeFileDetector().ban(normalized, "Admin web panel" + (reason.isEmpty() ? "" : ": " + reason));
                server.getFileIndex().removeFile(normalized);
                json(exchange, 200, Map.of("banned", normalized));
            }
            case "/api/unblock" -> {
                String hash = form.getOrDefault("hash", "").trim();
                if (!HASH.matcher(hash).matches())
                    throw new IllegalArgumentException("Hash must contain 32 hexadecimal characters");
                String normalized = hash.toUpperCase(Locale.ROOT);
                boolean wasBanned = server.getFakeFileDetector().unban(normalized);
                json(exchange, 200, Map.of("unbanned", normalized, "wasBanned", wasBanned));
            }
            case "/api/settings" -> json(exchange, 200, configResult(server.updateConfig(form)));
            case "/api/settings/reload" -> json(exchange, 200, configResult(server.reloadConfig()));
            case "/api/filters/reload" -> {
                String filterPath = server.getConfig().ipFilterPath();
                boolean reloaded = filterPath != null && !filterPath.isBlank();
                if (reloaded) server.reloadIpFilter(filterPath);
                server.reloadChildAbuseKeywords(server.getConfig().childAbuseKeywordsPath());
                json(exchange, 200, Map.of("reloaded", reloaded, "path", filterPath == null ? "" : filterPath));
            }
            case "/api/kick" -> {
                String ip = form.getOrDefault("ip", "").trim();
                if (!ip.isEmpty()) {
                    json(exchange, 200, Map.of("kicked", server.kickIp(ip), "ip", ip));
                    return;
                }
                String identifier = form.getOrDefault("clientId",
                        form.getOrDefault("identifier", "")).trim();
                if (identifier.isEmpty()) throw new IllegalArgumentException("clientId is required");
                json(exchange, 200, Map.of("kicked", server.kickClient(identifier) ? 1 : 0, "clientId", identifier));
            }
            default -> sendError(exchange, 404, "Not found");
        }
    }

    private Map<String, Object> status() {
        Runtime runtime = Runtime.getRuntime();
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        DatabaseManager database = server.getDatabaseManager();
        return Map.ofEntries(
                Map.entry("version", Main.VERSION),
                Map.entry("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - startedAt) / 1000)),
                Map.entry("connectedClients", server.getClientRegistry().size()),
                Map.entry("lowIdClients", server.getClientRegistry().lowIdCount()),
                Map.entry("indexedFiles", server.getFileIndex().fileCount()),
                Map.entry("ipFilterRanges", server.getIPFilter().getFilterCount()),
                Map.entry("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024),
                Map.entry("heapMaxMb", runtime.maxMemory() / 1024 / 1024),
                Map.entry("databaseReopens", database == null ? 0L : database.getReopenCount()),
                Map.entry("systemLoad", Double.isFinite(load) ? load : "unavailable")
        );
    }

    /** Headline figures of the Overview view, grouped by subject; the changes are read from the metrics history. */
    private Map<String, Object> overview() {
        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        ServerConfig config = server.getConfig();
        FileIndex index = server.getFileIndex();
        MetricsHistory history = server.getMetricsHistory();
        DatabaseManager database = server.getDatabaseManager();

        Map<String, Object> files = new java.util.LinkedHashMap<>();
        files.put("indexed", index.fileCount());
        files.put("max", config == null ? null : config.maxFiles());
        files.put("newSinceStart", index.totalNewFiles());
        files.put("ago24h", filesAt(history, now - 24 * 3_600_000L));
        files.put("ago7d", filesAt(history, now - 7 * 24 * 3_600_000L));
        MetricsHistory.Sample oldest = history == null ? null : history.oldest();
        files.put("historySince", oldest == null ? null : oldest.time());

        Map<String, Object> clients = new java.util.LinkedHashMap<>();
        clients.put("connected", server.getClientRegistry().size());
        clients.put("lowId", server.getClientRegistry().lowIdCount());
        clients.put("max", config == null ? null : config.maxUsers());

        Map<String, Object> memory = new java.util.LinkedHashMap<>();
        memory.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        memory.put("heapMaxMb", runtime.maxMemory() / 1024 / 1024);
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        memory.put("systemLoad", Double.isFinite(load) ? load : null);

        List<ServerGossip.ServerView> views = server.getServerViews();
        Map<String, Object> servers = new java.util.LinkedHashMap<>();
        servers.put("verified", views.stream().filter(v -> "verified".equals(v.state())).count());
        servers.put("known", views.size());

        KadSearchBridge kad = server.getKadSearchBridge();
        ServerSearchBridge serverBridge = server.getServerSearchBridge();
        GlobalSearchForwarder global = server.getGlobalSearchForwarder();
        Map<String, Object> searches = new java.util.LinkedHashMap<>();
        searches.put("kad", kad == null ? 0L : kad.getSearchesForked());
        searches.put("ed2kServers", serverBridge == null ? 0L : serverBridge.getSearchesForked());
        searches.put("udpGlobal", global == null ? 0L : global.getReceived());
        searches.put("filesIngested", (kad == null ? 0L : kad.getFilesIngested())
                + (serverBridge == null ? 0L : serverBridge.getFilesIngested()));

        Map<String, Object> protection = new java.util.LinkedHashMap<>();
        org.jemule.core.Md4Set banned = server.getFakeFileDetector() == null ? null
                : server.getFakeFileDetector().bannedHashes();
        protection.put("bannedHashes", banned == null ? 0 : banned.size());
        protection.put("ipFilterRanges", server.getIPFilter().getFilterCount());

        Map<String, Object> db = new java.util.LinkedHashMap<>();
        db.put("sizeMb", database == null ? null : database.fileSize() / 1024 / 1024);
        db.put("reopens", database == null ? 0L : database.getReopenCount());

        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("version", Main.VERSION);
        overview.put("uptimeSeconds", Math.max(0, (now - startedAt) / 1000));
        overview.put("files", files);
        overview.put("clients", clients);
        overview.put("memory", memory);
        overview.put("servers", servers);
        overview.put("searches", searches);
        overview.put("protection", protection);
        overview.put("database", db);
        return overview;
    }

    private static Integer filesAt(MetricsHistory history, long time) {
        MetricsHistory.Sample sample = history == null ? null : history.at(time);
        return sample == null ? null : sample.files();
    }

    /** Samples of the last {@code days} days (30 by default): {@code [time, files, clients]} each, oldest first. */
    private Map<String, Object> history(HttpExchange exchange) {
        int days = parseBounded(query(exchange.getRequestURI()).get("days"), 30, 1, 31);
        MetricsHistory history = server.getMetricsHistory();
        List<List<Long>> points = new ArrayList<>();
        if (history != null) {
            for (MetricsHistory.Sample sample : history.since(System.currentTimeMillis() - days * 24 * 3_600_000L)) {
                points.add(List.of(sample.time(), (long) sample.files(), (long) sample.clients()));
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("intervalSeconds", MetricsHistory.INTERVAL_MS / 1000);
        result.put("points", points);
        return result;
    }

    private void clients(HttpExchange exchange) throws IOException {
        String query = query(exchange.getRequestURI()).getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        int offset = parseBounded(query(exchange.getRequestURI()).get("offset"), 0, 0, 1_000_000);
        int limit = parseBounded(query(exchange.getRequestURI()).get("limit"), 100, 1, 100);
        List<Map<String, Object>> page = new ArrayList<>(limit);
        int total = 0;
        long now = System.currentTimeMillis();
        for (ClientState client : server.getClientRegistry().getAllClients()) {
            String nickname = client.nickname();
            String address = client.address().getHostAddress();
            String clientId = Long.toUnsignedString(client.clientId());
            if (!query.isEmpty() && !(nickname.toLowerCase(Locale.ROOT).contains(query)
                    || address.contains(query) || clientId.contains(query))) continue;
            if (total >= offset && page.size() < limit) {
                page.add(Map.of(
                        "nickname", nickname,
                        "ip", address,
                        "port", client.port(),
                        "listenPort", client.listenPort(),
                        "clientId", clientId,
                        "idType", client.isHighId() ? "HighID" : "LowID",
                        "publishedFiles", client.publishedFilesCount().get(),
                        "connectedSeconds", Math.max(0, (now - client.connectedAt()) / 1000)
                ));
            }
            total++;
        }
        json(exchange, 200, Map.of("total", total, "offset", offset, "limit", limit, "clients", page));
    }

    private List<Map<String, Object>> peers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerGossip.VerifiedPeer peer : server.getVerifiedServerPeers()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", peer.name());
            row.put("type", "eD2K server");
            row.put("address", peer.endpoint().address().getHostAddress());
            row.put("tcpPort", peer.endpoint().tcpPort());
            row.put("udpPort", peer.endpoint().udpPort());
            row.put("kadVersion", null);
            row.put("lastSeen", peer.verifiedAtMillis());
            result.add(row);
        }
        KadNode kadNode = server.getKadNode();
        if (kadNode != null) {
            for (KadContact contact : kadNode.getRoutingTable().getAllContacts()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", "Kad node " + contact.id());
                row.put("type", "Kad contact");
                row.put("address", contact.address().getHostAddress());
                row.put("tcpPort", contact.tcpPort());
                row.put("udpPort", contact.udpPort());
                row.put("kadVersion", contact.kadVersion());
                row.put("lastSeen", contact.lastSeen());
                result.add(row);
            }
        }
        return result;
    }

    private List<Map<String, Object>> servers() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerGossip.ServerView view : server.getServerViews()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", view.name());
            row.put("state", view.state());
            row.put("source", view.source().name().toLowerCase(Locale.ROOT));
            row.put("address", view.endpoint().address().getHostAddress());
            row.put("tcpPort", view.endpoint().tcpPort());
            row.put("udpPort", view.endpoint().udpPort());
            row.put("users", view.users());
            row.put("files", view.files());
            row.put("maxUsers", view.maxUsers());
            row.put("lowIdUsers", view.lowIdUsers());
            row.put("udpKey", view.hasUdpKey());
            row.put("latencyMs", view.latencyMillis());
            row.put("lastStatusSecondsAgo", view.lastStatusAtMillis() == null ? null
                    : Math.max(0, (now - view.lastStatusAtMillis()) / 1000));
            row.put("verifiedSecondsAgo", view.verifiedAtMillis() == null ? null
                    : Math.max(0, (now - view.verifiedAtMillis()) / 1000));
            row.put("missedProbes", view.missedStatusProbes());
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> ingestion() {
        FileIndex index = server.getFileIndex();
        Map<FileSource, Long> counts = index.newFileCounts();
        long total = index.totalNewFiles();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<FileSource, Long> entry : counts.entrySet()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("source", entry.getKey().label());
            row.put("newFiles", entry.getValue());
            row.put("percent", percent(entry.getValue(), total));
            rows.add(row);
        }
        Map<String, Object> totalRow = new java.util.LinkedHashMap<>();
        totalRow.put("source", "total");
        totalRow.put("newFiles", total);
        totalRow.put("percent", total == 0 ? 0.0 : 100.0);
        rows.add(totalRow);
        return rows;
    }

    private static double percent(long value, long total) {
        return total == 0 ? 0.0 : Math.round(value * 1000.0 / total) / 10.0;
    }

    private List<Map<String, Object>> searches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        KadSearchBridge kad = server.getKadSearchBridge();
        Map<String, Object> kadRow = new java.util.LinkedHashMap<>();
        kadRow.put("channel", "Kad");
        kadRow.put("queries", kad == null ? 0L : kad.getSearchesForked());
        kadRow.put("requests", kad == null ? 0L : kad.getRequestsSent());
        kadRow.put("results", kad == null ? 0L : kad.getResultsReceived());
        kadRow.put("ingested", kad == null ? 0L : kad.getFilesIngested());
        kadRow.put("active", kad == null ? 0 : kad.getActiveSessionCount());
        kadRow.put("blocked", kad == null ? 0L : kad.getIllicitFilesBlocked());
        kadRow.put("skipped", 0L);
        rows.add(kadRow);

        ServerSearchBridge servers = server.getServerSearchBridge();
        Map<String, Object> serverRow = new java.util.LinkedHashMap<>();
        serverRow.put("channel", "eD2K servers");
        serverRow.put("queries", servers == null ? 0L : servers.getSearchesForked());
        serverRow.put("requests", servers == null ? 0L : servers.getRequestsSent());
        serverRow.put("results", servers == null ? 0L : servers.getResultsReceived());
        serverRow.put("ingested", servers == null ? 0L : servers.getFilesIngested());
        serverRow.put("active", servers == null ? 0 : servers.getActiveSessionCount());
        serverRow.put("blocked", 0L);
        serverRow.put("skipped", 0L);
        rows.add(serverRow);

        // UDP global searches of other servers' clients: queries received, requests = searches started on Kad,
        // results = clean files found (all handed over to ingestion), blocked = refused (rate limits, child abuse
        // filter), skipped = duplicates, no keyword or Kad off. Their Kad traffic is also counted in the Kad row.
        GlobalSearchForwarder global = server.getGlobalSearchForwarder();
        Map<String, Object> globalRow = new java.util.LinkedHashMap<>();
        globalRow.put("channel", "UDP global searches (Kad)");
        globalRow.put("queries", global == null ? 0L : global.getReceived());
        globalRow.put("requests", global == null ? 0L : global.getForwarded());
        globalRow.put("results", global == null ? 0L : global.getResults());
        globalRow.put("ingested", global == null ? 0L : global.getResults());
        globalRow.put("active", null);
        globalRow.put("blocked", global == null ? 0L : global.getRefused());
        globalRow.put("skipped", global == null ? 0L : global.getSkipped());
        rows.add(globalRow);
        return rows;
    }

    private Map<String, Object> filters() {
        IPFilter ipFilter = server.getIPFilter();
        FakeFileDetector detector = server.getFakeFileDetector();
        int filterLimit = 100;
        List<String> ranges = ipFilter.getBlockedRanges(filterLimit);
        return Map.of(
                "ipFilterBlockedRanges", ipFilter.getFilterCount(),
                "ipFilterRangesShown", ranges.size(),
                "ipFilterRanges", ranges,
                "fakeFileHeuristicsEnabled", detector.isEnabled(),
                "heuristics", "multiple executable extensions, suspicious keywords, abnormal special-character density"
        );
    }

    private List<Map<String, Object>> bots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClientState client : server.getClientRegistry().getAllClients()) {
            if (AdminInterface.isPotentialBot(client.nickname())) {
                result.add(Map.of("nickname", client.nickname(), "ip", client.address().getHostAddress(),
                        "clientId", Long.toUnsignedString(client.clientId()), "classification", "nickname heuristic only"));
            }
        }
        return result;
    }

    private List<String> blocks() {
        DatabaseManager database = server.getDatabaseManager();
        return database == null ? List.of() : database.loadRecentBannedHashes(3);
    }

    private List<Map<String, Object>> settings() {
        var config = server.getConfig();
        List<Map<String, Object>> parameters = new ArrayList<>(ConfigParameter.ordered().size());
        Map<String, String> pending = server.pendingRestart();
        for (ConfigParameter parameter : ConfigParameter.ordered()) {
            Object value;
            if (parameter == ConfigParameter.FAKE_FILE_DETECTION_ENABLED) {
                value = server.getFakeFileDetector().isEnabled();
            } else {
                value = parameter.jsonValue(String.valueOf(config.value(parameter)));
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("key", parameter.key());
            entry.put("value", value);
            entry.put("type", parameter.type().name());
            entry.put("scope", parameter.scope().id());
            entry.put("scopeLabel", parameter.scope().label());
            entry.put("description", parameter.description());
            entry.put("pending", pending.getOrDefault(parameter.key(), ""));
            parameters.add(entry);
        }
        return parameters;
    }

    /** Serializes an applied/reloaded configuration result for the admin API. */
    private static Map<String, Object> configResult(ConfigApplyResult result) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("success", result.success());
        map.put("applied", result.applied());
        map.put("deferred", result.deferred());
        map.put("errors", result.errors());
        return map;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body is too large");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Expected form-encoded data");
        }
        return form(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery());
    }

    private static Map<String, String> form(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> values = new java.util.HashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static int parseBounded(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            return Math.clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isAllowedOrigin(String origin, int panelPort) {
        if (origin == null) return false;
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null || uri.getPort() != panelPort) return false;
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                return true;
            }
            try {
                InetAddress originAddr = InetAddress.getByName(host);
                return server.getAdminAccessFilter() == null || server.getAdminAccessFilter().isAllowed(originAddr);
            } catch (Exception e) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null) return false;
        String host = hostHeader;
        int requestPort = -1;
        int separator = hostHeader.lastIndexOf(':');
        if (separator > 0 && hostHeader.indexOf(':') == separator) {
            host = hostHeader.substring(0, separator);
            try {
                requestPort = Integer.parseInt(hostHeader.substring(separator + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (requestPort != -1 && requestPort != port()) {
            return false;
        }
        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return server.getAdminAccessFilter() == null || server.getAdminAccessFilter().isAllowed(addr);
        } catch (Exception e) {
            return false;
        }
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", encode(value));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message == null ? "Invalid request" : message));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + escape(text) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> encode(String.valueOf(e.getKey())) + ":" + encode(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(encode(item)));
            return String.join(",", items).transform(s -> "[" + s + "]");
        }
        return encode(value.toString());
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Stops the listener but keeps the panel reusable for a restart on another port. */
    synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        log.info("Admin web panel stopped");
    }

    @Override
    public synchronized void close() {
        stop();
        if (executor != null) {
            executor.close();
            executor = null;
        }
    }
}
