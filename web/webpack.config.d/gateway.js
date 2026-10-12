// Relative asset URLs let the same bundle run under the authenticated gateway's /friday/ path.
config.output.publicPath = '';
// Compose bundles are large; one minifier avoids spawning a worker per host CPU on small PCs.
if (config.mode === 'production') {
    const TerserPlugin = require('terser-webpack-plugin');
    config.optimization = {...config.optimization, minimizer: [new TerserPlugin({parallel: false})]};
}
