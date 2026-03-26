// polyfill.js
// 拦截 File System Access API，桥接到 Android 原生文件选择器
(function() {
    'use strict';

    // =========================================================
    // 调试日志
    // =========================================================
    function dbg(tag, msg) {
        console.log('[polyfill][' + tag + '] ' + msg);
    }

    // =========================================================
    // instanceof 兼容：让 polyfill 对象通过 instanceof 检查
    // VS Code 内部使用 handle instanceof FileSystemDirectoryHandle
    // 我们通过覆写 Symbol.hasInstance 来兼容
    // =========================================================
    var nativeFileHandle = (typeof FileSystemFileHandle !== 'undefined') ? FileSystemFileHandle : null;
    var nativeDirHandle = (typeof FileSystemDirectoryHandle !== 'undefined') ? FileSystemDirectoryHandle : null;

    if (nativeDirHandle) {
        Object.defineProperty(nativeDirHandle, Symbol.hasInstance, {
            value: function(instance) {
                return instance && instance.kind === 'directory' &&
                    typeof instance.getFileHandle === 'function' &&
                    typeof instance.getDirectoryHandle === 'function';
            }
        });
        dbg('init', 'Patched FileSystemDirectoryHandle[Symbol.hasInstance]');
    }
    if (nativeFileHandle) {
        Object.defineProperty(nativeFileHandle, Symbol.hasInstance, {
            value: function(instance) {
                return instance && instance.kind === 'file' &&
                    typeof instance.getFile === 'function';
            }
        });
        dbg('init', 'Patched FileSystemFileHandle[Symbol.hasInstance]');
    }

    // 同样 patch FileSystemHandle（isFileSystemHandle 检查用到的）
    if (typeof FileSystemHandle !== 'undefined') {
        Object.defineProperty(FileSystemHandle, Symbol.hasInstance, {
            value: function(instance) {
                return instance && typeof instance.kind === 'string' &&
                    typeof instance.queryPermission === 'function' &&
                    typeof instance.requestPermission === 'function';
            }
        });
        dbg('init', 'Patched FileSystemHandle[Symbol.hasInstance]');
    }

    // =========================================================
    // 回调注册表：Java 层通过 callbackId 回传结果
    // =========================================================
    const _callbacks = {};
    let _callbackId = 0;

    function nextCallbackId() {
        return 'cb_' + (++_callbackId);
    }

    // Java 层回调入口
    window.__fsaResolve = function(callbackId, jsonResult) {
        const cb = _callbacks[callbackId];
        if (cb) {
            delete _callbacks[callbackId];
            try {
                cb.resolve(JSON.parse(jsonResult));
            } catch(e) {
                dbg('resolve', 'JSON.parse failed: ' + e.message + ' raw=' + jsonResult);
                cb.reject(e);
            }
        }
    };
    window.__fsaReject = function(callbackId, error) {
        const cb = _callbacks[callbackId];
        if (cb) {
            delete _callbacks[callbackId];
            cb.reject(new DOMException(error, 'AbortError'));
        }
    };

    // 向 Java 层发起请求并等待异步回调
    function callNative(method, args) {
        return new Promise(function(resolve, reject) {
            const id = nextCallbackId();
            _callbacks[id] = { resolve: resolve, reject: reject };
            AndroidBridge.invoke(id, method, JSON.stringify(args || {}));
        });
    }

    // =========================================================
    // FSWritableStream — 模拟 FileSystemWritableFileStream
    // VS Code 的写入流程: createWritable() → write(Uint8Array) → close()
    // =========================================================
    function FSWritableStream(uri) {
        this._uri = uri;
        this._chunks = [];
        this._closed = false;
        dbg('FSWritableStream', 'created for uri=' + uri);
    }

    FSWritableStream.prototype.write = function(data) {
        if (this._closed) {
            return Promise.reject(new TypeError('Cannot write to a closed stream'));
        }
        dbg('FSWritableStream', 'write() called, type=' + (data instanceof Uint8Array ? 'Uint8Array' : typeof data) +
            ' size=' + (data.length || data.byteLength || 0));

        // 接受 Uint8Array、ArrayBuffer、string、或 { type: 'write', data: ... } 对象
        if (data && typeof data === 'object' && data.type === 'write') {
            data = data.data;
        }
        if (typeof data === 'string') {
            // 将字符串转为 Uint8Array (UTF-8)
            var encoder = new TextEncoder();
            data = encoder.encode(data);
        }
        if (data instanceof ArrayBuffer) {
            data = new Uint8Array(data);
        }
        this._chunks.push(data);
        return Promise.resolve();
    };

    FSWritableStream.prototype.close = function() {
        if (this._closed) {
            return Promise.reject(new TypeError('Stream already closed'));
        }
        this._closed = true;

        // 合并所有 chunks 为一个 Uint8Array
        var totalLen = 0;
        for (var i = 0; i < this._chunks.length; i++) {
            totalLen += this._chunks[i].length;
        }
        var merged = new Uint8Array(totalLen);
        var offset = 0;
        for (var i = 0; i < this._chunks.length; i++) {
            merged.set(this._chunks[i], offset);
            offset += this._chunks[i].length;
        }

        // 转为 base64 发送给 Java 层
        var binary = '';
        for (var i = 0; i < merged.length; i++) {
            binary += String.fromCharCode(merged[i]);
        }
        var base64 = btoa(binary);
        dbg('FSWritableStream', 'close() sending ' + merged.length + ' bytes (base64 len=' + base64.length + ')');

        return callNative('writeFile', { uri: this._uri, data: base64 }).then(function(result) {
            dbg('FSWritableStream', 'writeFile success: bytesWritten=' + result.bytesWritten);
        });
    };

    FSWritableStream.prototype.abort = function() {
        this._closed = true;
        this._chunks = [];
        dbg('FSWritableStream', 'abort()');
        return Promise.resolve();
    };

    // seek 和 truncate — VS Code 不使用但 spec 定义了
    FSWritableStream.prototype.seek = function(position) {
        dbg('FSWritableStream', 'seek() called (no-op)');
        return Promise.resolve();
    };

    FSWritableStream.prototype.truncate = function(size) {
        dbg('FSWritableStream', 'truncate() called (no-op)');
        return Promise.resolve();
    };

    // =========================================================
    // FileSystemFileHandle polyfill
    // =========================================================
    function FSFileHandle(name, uri) {
        this._name = name;
        this._uri = uri;
        this.kind = 'file';
        this.name = name;
    }

    FSFileHandle.prototype.getFile = function() {
        dbg('FSFileHandle', 'getFile() called: ' + this._name);
        return callNative('readFile', { uri: this._uri }).then(function(result) {
            // result: { data: base64, mimeType: string, lastModified: long, size: long }
            var binary = atob(result.data);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return new File([bytes], result.name || this._name, {
                type: result.mimeType || 'application/octet-stream',
                lastModified: result.lastModified || Date.now()
            });
        }.bind(this));
    };

    FSFileHandle.prototype.queryPermission = function(desc) {
        return Promise.resolve('granted');
    };

    FSFileHandle.prototype.requestPermission = function(desc) {
        return Promise.resolve('granted');
    };

    FSFileHandle.prototype.isSameEntry = function(other) {
        return Promise.resolve(this._uri === (other && other._uri));
    };

    FSFileHandle.prototype.createWritable = function(options) {
        dbg('FSFileHandle', 'createWritable() called: ' + this._name);
        return Promise.resolve(new FSWritableStream(this._uri));
    };

    // =========================================================
    // FileSystemDirectoryHandle polyfill
    // =========================================================
    function FSDirHandle(name, uri) {
        this._name = name;
        this._uri = uri;
        this.kind = 'directory';
        this.name = name;
    }

    FSDirHandle.prototype.queryPermission = function(desc) {
        return Promise.resolve('granted');
    };

    FSDirHandle.prototype.requestPermission = function(desc) {
        return Promise.resolve('granted');
    };

    FSDirHandle.prototype.isSameEntry = function(other) {
        return Promise.resolve(this._uri === (other && other._uri));
    };

    FSDirHandle.prototype.removeEntry = function(name, options) {
        dbg('FSDirHandle', 'removeEntry() called: dir=' + this._name + ' name=' + name +
            ' recursive=' + (options && options.recursive));
        var dirUri = this._uri;
        // 先通过 getChildEntry 找到子条目的 URI，再调用 deleteEntry
        return callNative('getChildEntry', { uri: dirUri, name: name, type: 'any' }).then(function(result) {
            if (result.error) {
                throw new DOMException(result.error, 'NotFoundError');
            }
            return callNative('deleteEntry', { uri: result.uri });
        });
    };

    FSDirHandle.prototype.getFileHandle = function(name, options) {
        dbg('FSDirHandle', 'getFileHandle() called: dir=' + this._name + ' name=' + name +
            ' create=' + (options && options.create));
        if (options && options.create) {
            var self = this;
            dbg('FSDirHandle', 'getFileHandle() creating file: ' + name);
            return callNative('createFile', { uri: this._uri, name: name }).then(function(result) {
                return new FSFileHandle(result.name, result.uri);
            });
        }
        return callNative('getChildEntry', { uri: this._uri, name: name, type: 'file' }).then(function(result) {
            if (result.error) {
                throw new DOMException(result.error, 'NotFoundError');
            }
            return new FSFileHandle(result.name, result.uri);
        });
    };

    FSDirHandle.prototype.getDirectoryHandle = function(name, options) {
        dbg('FSDirHandle', 'getDirectoryHandle() called: dir=' + this._name + ' name=' + name +
            ' create=' + (options && options.create));
        if (options && options.create) {
            dbg('FSDirHandle', 'getDirectoryHandle() creating dir: ' + name);
            return callNative('createDirectory', { uri: this._uri, name: name }).then(function(result) {
                return new FSDirHandle(result.name, result.uri);
            });
        }
        return callNative('getChildEntry', { uri: this._uri, name: name, type: 'directory' }).then(function(result) {
            if (result.error) {
                throw new DOMException(result.error, 'NotFoundError');
            }
            return new FSDirHandle(result.name, result.uri);
        });
    };

    // 异步迭代器：遍历目录中的条目
    FSDirHandle.prototype.entries = function() {
        dbg('FSDirHandle', 'entries() called: ' + this._name);
        var dirUri = this._uri;
        var entriesResult = null;
        var index = 0;

        return {
            next: function() {
                if (entriesResult === null) {
                    return callNative('listDirectory', { uri: dirUri }).then(function(result) {
                        entriesResult = result.entries || [];
                        return this.next();
                    }.bind(this));
                }
                if (index < entriesResult.length) {
                    var entry = entriesResult[index++];
                    var handle;
                    if (entry.type === 'directory') {
                        handle = new FSDirHandle(entry.name, entry.uri);
                    } else {
                        handle = new FSFileHandle(entry.name, entry.uri);
                    }
                    return Promise.resolve({ value: [entry.name, handle], done: false });
                }
                return Promise.resolve({ done: true });
            },
            [Symbol.asyncIterator]: function() { return this; }
        };
    };

    FSDirHandle.prototype.values = function() {
        var iter = this.entries();
        return {
            next: function() {
                return iter.next().then(function(result) {
                    if (result.done) return result;
                    return { value: result.value[1], done: false };
                });
            },
            [Symbol.asyncIterator]: function() { return this; }
        };
    };

    FSDirHandle.prototype.keys = function() {
        var iter = this.entries();
        return {
            next: function() {
                return iter.next().then(function(result) {
                    if (result.done) return result;
                    return { value: result.value[0], done: false };
                });
            },
            [Symbol.asyncIterator]: function() { return this; }
        };
    };

    FSDirHandle.prototype[Symbol.asyncIterator] = function() {
        return this.entries();
    };

    // =========================================================
    // 覆盖全局 API
    // =========================================================
    window.showDirectoryPicker = function(options) {
        dbg('API', 'showDirectoryPicker called');
        return callNative('pickDirectory', options || {}).then(function(result) {
            if (result.error) {
                throw new DOMException(result.error, 'AbortError');
            }
            var handle = new FSDirHandle(result.name, result.uri);
            dbg('API', 'showDirectoryPicker returning: name=' + handle.name +
                ' instanceof FileSystemDirectoryHandle=' +
                (nativeDirHandle ? (handle instanceof nativeDirHandle) : 'N/A'));
            return handle;
        });
    };

    window.showOpenFilePicker = function(options) {
        return callNative('pickFile', options || {}).then(function(result) {
            if (result.error) {
                throw new DOMException(result.error, 'AbortError');
            }
            var handles = [];
            var files = result.files || [result];
            for (var i = 0; i < files.length; i++) {
                handles.push(new FSFileHandle(files[i].name, files[i].uri));
            }
            return handles;
        });
    };

    console.log('[polyfill] File System Access API polyfill loaded');
})();
