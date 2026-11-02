import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "export",
  // docs/results/index.html rather than docs/results.html, so any static file server finds every page.
  trailingSlash: true,
  images: { unoptimized: true },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
