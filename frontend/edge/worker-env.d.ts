declare namespace Cloudflare {
  interface Env {
    ASSETS: Fetcher;
    API_ORIGIN: string;
    API_ORIGIN_TOKEN?: string;
  }

  interface GlobalProps {
    mainModule: typeof import('./worker');
  }
}
