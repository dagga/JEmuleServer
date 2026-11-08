// The panel is exported as static files (out/), packaged in the server JAR and served by the admin HTTP server:
// no Node runtime on the server, no external resource, every route ends with a slash (out/clients/index.html).
/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'export',
  trailingSlash: true,
  reactStrictMode: true,
  poweredByHeader: false,
  productionBrowserSourceMaps: false,
  images: { unoptimized: true },
  devIndicators: false,
};

export default nextConfig;
