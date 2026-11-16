;(function () {
  if (window.top !== window || window.__DSH_PAL_PLUGIN_POLICY__) return;
  var requested;
  try { requested = JSON.parse(DshFiles.pausedWebPlugins()); } catch (_) { requested = []; }
  var wallpaper = 'dsh-plugin-wallpaper-engine';
  var report = { supported: false, packages: [], paused: [], required: [] };
  window.__DSH_PAL_PLUGIN_POLICY__ = { report: function () { return report; } };
  if (Object.prototype.hasOwnProperty.call(window, '__DSH_BOOT__')) return;
  var manifest;
  function apply(value) {
    if (!value || !Array.isArray(value.entries) || !Array.isArray(value.batches)) return value;
    var entries = value.entries;
    var batches = value.batches;
    if (entries.length > 512 || batches.length > 512 ||
        entries.some(function (row) { return !row || typeof row.id !== 'string'; }) ||
        batches.some(function (batch) { return !batch || !Array.isArray(batch.entries); })) return value;
    var ids = entries.map(function (row) { return row.id; });
    var pauseWallpaper = requested.indexOf(wallpaper) !== -1;
    var paused = ids.filter(function (id) {
      return requested.indexOf(id) !== -1 || (pauseWallpaper && id.indexOf(wallpaper + '-') === 0);
    });
    var retained = [];
    // Keep explicitly imported dependencies of modules that stay enabled. Repeat
    // because a newly retained module can import another requested dependency.
    for (;;) {
      var required = [];
      entries.forEach(function (row) {
        if (paused.indexOf(row.id) !== -1) return;
        var dependencies = (Array.isArray(row.external) ? row.external : [])
          .concat(Array.isArray(row.inject) ? row.inject : []);
        dependencies.forEach(function (specifier) {
          if (typeof specifier !== 'string') return;
          var id = specifier.split('/').slice(0, specifier[0] === '@' ? 2 : 1).join('/');
          if (paused.indexOf(id) !== -1 && required.indexOf(id) === -1) required.push(id);
        });
      });
      if (!required.length) break;
      paused = paused.filter(function (id) { return required.indexOf(id) === -1; });
      retained = retained.concat(required);
    }
    report = { supported: true, packages: ids, paused: paused, required: retained };
    if (!paused.length) return value;
    // Keep URLs and revisions intact so the browser can reuse the same bundles.
    // The boot manifest determines which registered factories are activated.
    return Object.assign({}, value, {
      entries: entries.filter(function (row) { return paused.indexOf(row.id) === -1; }),
      batches: batches.map(function (batch) {
        return Object.assign({}, batch, {
          entries: batch.entries.filter(function (id) { return paused.indexOf(id) === -1; })
        });
      }).filter(function (batch) { return batch.entries.length > 0; })
    });
  }
  Object.defineProperty(window, '__DSH_BOOT__', {
    configurable: true, enumerable: true,
    get: function () { return manifest; },
    set: function (value) {
      manifest = value;
      try { manifest = apply(value); } catch (_) {}
    }
  });

  // The connected client receives a fresh manifest after boot. Apply the same
  // policy there so a server sync cannot reactivate locally paused modules.
  if (!requested.length || Object.prototype.hasOwnProperty.call(window, '__ModuleLoader__')) return;
  var loader;
  Object.defineProperty(window, '__ModuleLoader__', {
    configurable: true, enumerable: true,
    get: function () { return loader; },
    set: function (value) {
      loader = value;
      Object.defineProperty(window, '__ModuleLoader__', {
        configurable: true, enumerable: true, writable: true, value: value
      });
      if (!value || typeof value.create !== 'function') return;
      var create = value.create;
      value.create = function () {
        var modules = create.apply(this, arguments);
        var entries = modules && modules.entries;
        if (entries && typeof entries.sync === 'function') {
          var sync = entries.sync;
          entries.sync = function (graph) {
            return sync.call(this, apply(graph));
          };
        }
        return modules;
      };
    }
  });
})();
