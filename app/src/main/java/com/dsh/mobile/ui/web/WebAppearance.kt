package com.dsh.mobile.ui.web

import com.dsh.mobile.core.LocalAppearance
import org.json.JSONObject

/** Purely local presentation. Never requests a plugin or writes remote settings. */
object WebAppearance {
    fun script(): String {
        val palettes = JSONObject().apply { LocalAppearance.entries.filter { it != LocalAppearance.DSH }.forEach { theme ->
            put(theme.id, JSONObject().put("background", theme.background).put("surface", theme.surface).put("accent", theme.accent)
                .put("foreground",theme.foreground).put("muted",theme.muted).put("onAccent",theme.onAccent).put("light",theme.isLight))
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
            var p=palettes[id];
            if(/^custom:\d{1,3}:\d{1,3}:\d{1,3}$/.test(id)){
              try{p=JSON.parse(DshFiles.localAppearancePalette());}catch(_){p=null;}
            }
            if(!p){root.removeAttribute('data-dsh-local-theme');if(style)style.remove();return;}
            if(!style){style=document.createElement('style');style.id='dsh-local-appearance';root.appendChild(style);}
            style.textContent=`
              html[data-dsh-local-theme] {color-scheme:SCHEME;}
              html[data-dsh-local-theme] body {
                color:FOREGROUND!important;background:BACKGROUND!important;
                --dsw-alias-label-primary:FOREGROUND!important;
                --dsw-alias-label-primary-bluish:FOREGROUND!important;
                --dsw-alias-label-primary-dimmed:FOREGROUND!important;
                --dsw-alias-label-secondary:MUTED!important;
                --dsw-alias-label-tertiary:MUTED!important;
                --dsw-alias-label-caption:MUTED!important;
                --dsw-alias-label-dimmed:MUTED!important;
                --dsw-alias-bg-base:BACKGROUND!important;
                --dsw-alias-bg-layer-1:SURFACE!important;
                --dsw-alias-bg-layer-2:SURFACE!important;
                --dsw-alias-bg-layer-3:SURFACE!important;
                --dsw-alias-border-l1:color-mix(in srgb,FOREGROUND 38%,SURFACE)!important;
                --dsw-alias-border-l2:color-mix(in srgb,FOREGROUND 30%,SURFACE)!important;
                --dsw-alias-border-l2-darkmode-thin:color-mix(in srgb,FOREGROUND 30%,SURFACE)!important;
                --dsw-alias-border-l3:color-mix(in srgb,FOREGROUND 24%,SURFACE)!important;
                --dsw-alias-border-l4:color-mix(in srgb,FOREGROUND 20%,SURFACE)!important;
                --dsw-alias-interactive-bg-hover:color-mix(in srgb,ACCENT 12%,SURFACE)!important;
                --dsw-alias-interactive-bg-hover-solid:color-mix(in srgb,ACCENT 18%,SURFACE)!important;
                --dsw-alias-interactive-bg-active:color-mix(in srgb,ACCENT 22%,SURFACE)!important;
                --dsw-alias-markdown-code-block:SURFACE!important;
                --dsw-alias-markdown-code:color-mix(in srgb,ACCENT 10%,SURFACE)!important;
                --dsw-alias-link:ACCENT!important;
                --dsw-specific-sidebar-fill:SURFACE!important;
                --dsw-specific-input-major:SURFACE!important;
                --dsw-specific-bubble:SURFACE!important;
                --dsw-specific-bubble-highlight:SURFACE!important;
                --dsw-alias-settings-card-fill:SURFACE!important;
                --dsw-specific-menu:SURFACE!important;
                --dsw-alias-button-primary-fill:ACCENT!important;
                --dsw-alias-state-business-primary:ACCENT!important;
              }
              html[data-dsh-local-theme] [data-dsh-mobile-frame],
              html[data-dsh-local-theme] [data-dsh-mobile-center],
              html[data-dsh-local-theme] [data-dsh-mobile-center] [data-phase]:has([data-composer-seat]) {background:BACKGROUND!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-dsh-mobile-header] {background:BACKGROUND!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-composer-seat] [class$="_card"],
              html[data-dsh-local-theme] [data-dsh-mobile-sidebar],
              html[data-dsh-local-theme] [data-dsh-mobile-sidebar] [class$="_logoRow"],
              html[data-dsh-local-theme] [data-dsh-mobile-menu],
              html[data-dsh-local-theme] [data-dsh-mobile-dialog],
              html[data-dsh-local-theme] [data-dsh-mobile-popup],
              html[data-dsh-local-theme] [data-dsh-mobile-right],
              html[data-dsh-local-theme] [data-dsh-mobile-right-surface],
              html[data-dsh-local-theme] [data-dsh-mobile-right] [class*="_tabHostHeader_"] {background:SURFACE!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-composer-seat] [class$="_card"] {
                border:1px solid color-mix(in srgb,FOREGROUND 30%,SURFACE)!important;
                box-shadow:0 2px 10px color-mix(in srgb,FOREGROUND 8%,transparent)!important;
              }
              html:root[data-dsh-local-theme] body [data-changed-files] {
                background:SURFACE!important;background-image:none!important;color:FOREGROUND!important;
                border:1px solid color-mix(in srgb,FOREGROUND 30%,SURFACE)!important;
                --changes-fill:SURFACE!important;
                --changes-hover:color-mix(in srgb,ACCENT 14%,SURFACE)!important;
              }
              html:root[data-dsh-local-theme] body [data-changed-files] [class$="_header"],
              html:root[data-dsh-local-theme] body [data-presented-file] {
                background:SURFACE!important;background-image:none!important;color:FOREGROUND!important;
              }
              html:root[data-dsh-local-theme] body [data-presented-file] {
                border:1px solid color-mix(in srgb,FOREGROUND 30%,SURFACE)!important;
                border-radius:12px!important;box-sizing:border-box!important;
              }
              html[data-dsh-local-theme] [data-composer-seat] [data-composer-input],
              html[data-dsh-local-theme] [data-composer-seat] [role="textbox"][contenteditable="true"] {background:transparent!important;background-image:none!important;}
              html[data-dsh-local-theme] [data-composer-seat] button[class$="_add"] {background:color-mix(in srgb,SURFACE 82%,white 18%)!important;}
              html[data-dsh-local-theme] [data-composer-seat] button[class*="_primary"][aria-label="发送消息"],
              html[data-dsh-local-theme] [data-composer-seat] button[class*="_primary"][aria-label="Send message"] {background:ACCENT!important;color:ONACCENT!important;}
              html[data-dsh-local-theme] body {--we-accent:ACCENT!important;--we-ui-send:ACCENT!important;--we-ui-link:ACCENT!important;}
              html[data-dsh-local-theme] .dsh-tab-indicator {background:ACCENT!important;}
            `.replaceAll('BACKGROUND',p.background).replaceAll('SURFACE',p.surface)
              .replaceAll('ONACCENT',p.onAccent).replaceAll('ACCENT',p.accent)
              .replaceAll('FOREGROUND',p.foreground).replaceAll('MUTED',p.muted).replaceAll('SCHEME',p.light?'light':'dark');
            root.setAttribute('data-dsh-local-theme',id);
            try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:'local appearance applied; preset='+id+'; remote settings unchanged'}));}catch(_){}
          }
          window.__DSH_APPEARANCE__={apply:apply};apply();
          if(!document.documentElement){var observer=new MutationObserver(function(){if(document.documentElement){observer.disconnect();apply();}});observer.observe(document,{childList:true,subtree:true});}
        })();
        """.trimIndent()
    }
}
