const API_TARGET = process.env.VARVIS_API_BASE || 'http://localhost:8086';

const createProxyConfig = (target) => ({
    target,
    changeOrigin: true,
    secure: false,
    timeout: 15 * 60 * 1000,
    proxyTimeout: 15 * 60 * 1000,
});

export default {
    dev: {
        '/variantBasedMap/': createProxyConfig(API_TARGET),
        '/variant-analyses': createProxyConfig(API_TARGET),
    },
    test: {
        '/variantBasedMap/': createProxyConfig(API_TARGET),
        '/variant-analyses': createProxyConfig(API_TARGET),
    },
    pre: {
        '/variantBasedMap/': createProxyConfig(API_TARGET),
        '/variant-analyses': createProxyConfig(API_TARGET),
    },
};
