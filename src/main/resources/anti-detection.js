(() => {
    "use strict";
    /* -------------------------------------------------------
     * 0. 极早期覆写 navigator.webdriver 属性，彻底消除 Playwright 标记
     * ----------------------------------------------------- */
    try {
        if (navigator.webdriver !== undefined) {
            Object.defineProperty(navigator, 'webdriver', {
                get: () => undefined,
                configurable: true
            });
        }
    } catch (_) {}

    /* -------------------------------------------------------
     * 1. 保存原生 Function.prototype.toString
     * ----------------------------------------------------- */
    const nativeFunctionToString = Function.prototype.toString;

    /* -------------------------------------------------------
     * 2. WeakMap：函数 → 伪原生源码
     * ----------------------------------------------------- */
    const nativeSourceMap = new WeakMap();

    /* -------------------------------------------------------
     * 3. 注册伪原生源码
     * ----------------------------------------------------- */
    const registerNativeSource = (fn, source) => {
      try {
        nativeSourceMap.set(fn, source);
      } catch (_) {}
    };

    /* -------------------------------------------------------
     * 4. 劫持 Function.prototype.toString
     * ----------------------------------------------------- */
    Object.defineProperty(Function.prototype, "toString", {
      configurable: true,
      writable: true,
      value: function toString() {
        if (nativeSourceMap.has(this)) {
          return nativeSourceMap.get(this);
        }
        return nativeFunctionToString.call(this);
      },
    });

    /* -------------------------------------------------------
     * 5. 伪装 Function.prototype.toString 自身
     * ----------------------------------------------------- */
    registerNativeSource(
      Function.prototype.toString,
      nativeFunctionToString.toString(),
    );

    /* -------------------------------------------------------
     * 6. stealthify：包装函数但保持“原生外观”
     * ----------------------------------------------------- */
    const stealthify = (obj, prop, handler) => {
      const original = obj[prop];
      if (typeof original !== "function") return;

      const wrapped = function (...args) {
        return handler.call(this, original, args);
      };
      const namePropertyDescriptor = Object.getOwnPropertyDescriptor(
        wrapped,
        "name",
      );
      // 处理函数 name 属性
      Object.defineProperty(wrapped, "name", {
        ...namePropertyDescriptor,
        value: prop,
      });
      // 保留 prototype
      try {
        Object.setPrototypeOf(wrapped, Object.getPrototypeOf(original));
      } catch (_) {}

      // 注册伪原生源码
      registerNativeSource(wrapped, nativeFunctionToString.call(original));

      // 用 defineProperty 保持 descriptor 接近原生
      const desc = Object.getOwnPropertyDescriptor(obj, prop);
      Object.defineProperty(obj, prop, {
        ...desc,
        value: wrapped,
      });
    };

    /* -------------------------------------------------------
     * 7. 劫持 console 以防 CDP 耗时差检测
     * ----------------------------------------------------- */
    const filterConsoleArgs = (args) =>
      args.map((arg) => {
        if (arg && typeof arg === "object") {
          return {}; // 防止 getter / Proxy / 大对象触发
        }
        return arg;
      });

    // 常规 console 输出：过滤对象参数防止 CDP 序列化时触发 getter 检测
    ["log", "debug", "info", "warn", "error", "dir", "debug"].forEach(
      (name) => {
        stealthify(console, name, function(original, args) {
          return original.apply(this, filterConsoleArgs(args));
        });
      },
    );

    // console.table 特殊处理：完全空化，直接返回，消除大数组渲染时间，直接破解 checkDevTools 时间差检测
    stealthify(console, "table", function(original, args) {
      return;
    });

    /* -------------------------------------------------------
     * 8. 劫持 performance.now 防止 disable-devtool 时序差反调试
     * ----------------------------------------------------- */
    if (typeof performance !== "undefined" && typeof performance.now === "function") {
      const navStart = performance.timing ? performance.timing.navigationStart : Date.now();
      let lastNow = Date.now() - navStart;
      let counter = 0;

      stealthify(performance, "now", function(original, args) {
        let current = Date.now() - navStart;
        if (current <= lastNow) {
          counter += 0.001; // 递增 1 微秒等值，以防 t1===t2 触发时序判定 hook
          current = lastNow + counter;
        } else {
          lastNow = current;
          counter = 0;
        }
        return current;
      });
    }

    /* -------------------------------------------------------
     * 9. 劫持网络与 iframe，拦截针对本地回环地址（RPA / 调试端口）的扫描（支持 IPv4 / IPv6）
     * ----------------------------------------------------- */
    const isLocalAddress = (url) => {
      if (typeof url !== 'string') return false;
      const lower = url.toLowerCase();
      return lower.includes('127.0.0.1') || 
             lower.includes('localhost') || 
             lower.includes('0.0.0.0') || 
             lower.includes('[::1]') || 
             lower.includes('::1');
    };

    // 9.1 拦截 WebSocket 扫描
    const OriginalWebSocket = window.WebSocket;
    if (typeof OriginalWebSocket === 'function') {
      const WebSocketProxy = new Proxy(OriginalWebSocket, {
        construct(target, args) {
          const url = args[0];
          if (isLocalAddress(url)) {
            console.warn('Blocked WebSocket connection to local address:', url);
            throw new Error('Connection refused');
          }
          return Reflect.construct(target, args);
        }
      });
      registerNativeSource(WebSocketProxy, "function WebSocket() { [native code] }");
      window.WebSocket = WebSocketProxy;
    }

    // 9.2 拦截 fetch 扫描
    if (typeof window.fetch === 'function') {
      stealthify(window, "fetch", function(original, args) {
        const input = args[0];
        let url = "";
        if (typeof input === 'string') {
          url = input;
        } else if (input && typeof input === 'object' && 'url' in input) {
          url = input.url;
        }
        if (isLocalAddress(url)) {
          return Promise.reject(new TypeError('Failed to fetch'));
        }
        return original.apply(this, args);
      });
    }

    // 9.3 拦截 XMLHttpRequest 扫描
    if (typeof XMLHttpRequest !== 'undefined') {
      stealthify(XMLHttpRequest.prototype, "open", function(original, args) {
        const url = args[1];
        if (isLocalAddress(url)) {
          args[1] = 'about:blank';
        }
        return original.apply(this, args);
      });
    }

    // 9.4 拦截 iframe 扫描与元素属性探测
    const originalIframeSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'src');
    if (originalIframeSrcDescriptor && originalIframeSrcDescriptor.set) {
      const originalSet = originalIframeSrcDescriptor.set;
      Object.defineProperty(HTMLIFrameElement.prototype, 'src', {
        ...originalIframeSrcDescriptor,
        set: function (val) {
          if (isLocalAddress(val)) {
            console.warn('Blocked iframe navigation to local address:', val);
            return originalSet.call(this, 'about:blank');
          }
          return originalSet.call(this, val);
        }
      });
    }
    stealthify(Element.prototype, "setAttribute", function (original, args) {
      const [name, val] = args;
      if (typeof name === 'string' && (name.toLowerCase() === 'src' || name.toLowerCase() === 'href') && typeof val === 'string') {
        if (isLocalAddress(val)) {
          args[1] = 'about:blank';
        }
      }
      return original.apply(this, args);
    });

    /* -------------------------------------------------------
     * 10. 防御性补丁
     * ----------------------------------------------------- */
    // 防止检测 toString 被替换
    registerNativeSource(
      registerNativeSource,
      "function registerNativeSource() { [native code] }",
    );
})();