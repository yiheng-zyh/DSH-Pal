package com.dsh.mobile.ui.web

import com.dsh.mobile.core.LocalAppearance
import org.json.JSONObject

/** Purely local presentation. Never requests a plugin or writes remote settings. */
object WebAppearance {
    fun script(): String {
        val palettes = JSONObject().apply { LocalAppearance.entries.filter { it != LocalAppearance.DSH }.forEach { theme ->
            put(theme.id, JSONObject().put("background", theme.background).put("surface", theme.surface).put("accent", theme.accent))
        } }
        return """
        (function(){
          if(window.top!==window)return;
          if(window.__DSH_APPEARANCE__){window.__DSH_APPEARANCE__.apply();return;}
          var palettes=$palettes,last=null;
          function apply(){
            if(!document.documentElement)return;
            var id='dsh';try{id=DshFiles.localAppearance();}catch(_){}
            if(id===last)return;last=id;
            var root=document.documentElement,style=document.getElementById('dsh-local-appearance');
            if(!palettes[id]){root.removeAttribute('data-dsh-local-theme');if(style)style.remove();return;}
            if(!style){style=document.createElement('style');style.id='dsh-local-appearance';root.appendChild(style);}
            var p=palettes[id];
            style.textContent=`
              html[data-dsh-local-theme] [data-dsh-mobile-frame],
              html[data-dsh-local-theme] [data-dsh-mobile-center],
              html[data-dsh-local-theme] [data-dsh-mobile-center] [data-phase]:has([data-composer-seat]) {background:BACKGROUND!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-dsh-mobile-header],
              html[data-dsh-local-theme] [data-composer-seat] [class$="_card"],
              html[data-dsh-local-theme] [data-dsh-mobile-sidebar],
              html[data-dsh-local-theme] [data-dsh-mobile-sidebar] [class$="_logoRow"],
              html[data-dsh-local-theme] [data-dsh-mobile-menu],
              html[data-dsh-local-theme] [data-dsh-mobile-dialog],
              html[data-dsh-local-theme] [data-dsh-mobile-popup],
              html[data-dsh-local-theme] [data-dsh-mobile-right],
              html[data-dsh-local-theme] [data-dsh-mobile-right-surface],
              html[data-dsh-local-theme] [data-dsh-mobile-right] [class*="_tabHostHeader_"] {background:SURFACE!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-composer-seat] [data-composer-input],
              html[data-dsh-local-theme] [data-composer-seat] [role="textbox"][contenteditable="true"] {background:transparent!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-composer-seat] button[class$="_add"] {background:color-mix(in srgb,SURFACE 82%,white 18%)!important;}
              html[data-dsh-local-theme] [data-composer-seat] button[class*="_primary"][aria-label="发送消息"],
              html[data-dsh-local-theme] [data-composer-seat] button[class*="_primary"][aria-label="Send message"] {background:ACCENT!important;color:BACKGROUND!important;}
              html[data-dsh-local-theme] body {--we-accent:ACCENT!important;--we-ui-send:ACCENT!important;--we-ui-link:ACCENT!important;}
              html[data-dsh-local-theme] .dsh-tab-indicator {background:ACCENT!important;}
            `.replaceAll('BACKGROUND',p.background).replaceAll('SURFACE',p.surface).replaceAll('ACCENT',p.accent);
            root.setAttribute('data-dsh-local-theme',id);
            try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:'local appearance applied; preset='+id+'; remote settings unchanged'}));}catch(_){}
          }
          window.__DSH_APPEARANCE__={apply:apply};apply();
          if(!document.documentElement){var observer=new MutationObserver(function(){if(document.documentElement){observer.disconnect();apply();}});observer.observe(document,{childList:true,subtree:true});}
        })();
        """.trimIndent()
    }
}
