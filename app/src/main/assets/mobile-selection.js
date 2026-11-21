;(function() {
  if (window.top !== window || window.__DSH_MOBILE_SELECTION__) return;
  function target() {
    var selection = getSelection();
    if (!selection || selection.rangeCount !== 1 || selection.isCollapsed || !selection.toString().trim()) return null;
    var range = selection.getRangeAt(0);
    function source(node) {
      var element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      if (!element || element.closest('input,textarea,[contenteditable="true"],[data-text-interaction-exclude]')) return null;
      return element.closest('[data-text-interaction-source]');
    }
    var owner = source(range.startContainer);
    if (!owner || owner !== source(range.endContainer) || !owner.closest('[data-dsh-mobile-center]') ||
        owner.closest('[hidden],[inert],[data-text-interaction-exclude]')) return null;
    var text = selection.toString().trim();
    try {
      var entry = window.__dshSidebarModuleSystem__.entries.managed.get('@deepseek-ai/dsh-client-ui-chat');
      var current = entry.ctx.get('uiSession').adapter.current.getSnapshot();
      var interactions = entry.ctx.get('uiConversation').interactions;
      var admitted = interactions.dispatch(current.key, {trigger: 'selection', hasSelection: true,
        readText: function() { return text; }, preventDefault: function() {}});
      if (admitted !== 'quote' || typeof current.props.inputActions.insertQuote !== 'function') return null;
      return {text: text, actions: current.props.inputActions};
    } catch (_) { return null; }
  }
  var style = document.createElement('style');
  style.textContent = 'html[data-pal-native-selection] [data-text-interaction-surface]{visibility:hidden!important;pointer-events:none!important}';
  function mount() { if (!style.isConnected && document.documentElement) document.documentElement.append(style); }
  mount();
  if (!document.documentElement) document.addEventListener('DOMContentLoaded', mount, {once: true});
  window.__DSH_MOBILE_SELECTION__ = {
    active: function(value) {
      if (document.documentElement) document.documentElement.toggleAttribute('data-pal-native-selection', !!value);
      return !!target();
    },
    available: function() { return !!target(); },
    quote: function() {
      var selected = target();
      if (!selected || !selected.actions.insertQuote(selected.text)) return false;
      getSelection().removeAllRanges();
      return true;
    }
  };
})();
