/**
 * AiCodeR 反馈收集中转（Cloudflare Worker）。
 *
 * App 直接 POST JSON 到这里，不需要用户登录；GitHub 令牌只存在服务端。
 * 部署后把 URL（以及可选的 FEEDBACK_KEY）写进仓库 data 分支的
 * data/feedback-endpoint.json，App 会自动读取并提交。
 */
export default {
  async fetch(request, env) {
    if (request.method !== 'POST') {
      return new Response('method not allowed', { status: 405 });
    }
    if (env.FEEDBACK_KEY && request.headers.get('X-Feedback-Key') !== env.FEEDBACK_KEY) {
      return new Response('forbidden', { status: 403 });
    }

    let data;
    try {
      data = await request.json();
    } catch {
      return new Response('bad json', { status: 400 });
    }
    const title = String(data.title || '').trim();
    const body = String(data.body || '').trim();
    if (!title || !body) return new Response('title/body required', { status: 400 });

    const meta = [
      '',
      '---',
      `- installId: \`${data.installId || 'unknown'}\``,
      `- app: ${data.appVersion || 'unknown'}`,
      `- device: ${data.manufacturer || ''} ${data.device || ''}`,
      `- android: ${data.android || 'unknown'} (${data.abi || ''})`,
      `- kind: ${data.kind || 'unknown'}`,
      `- createdAt: ${data.createdAt || ''}`
    ].join('\n');

    const res = await fetch(
      `https://api.github.com/repos/${env.REPO_OWNER || 'EzRlon'}/${env.REPO_NAME || 'AiCodeR'}/issues`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN}`,
          Accept: 'application/vnd.github+json',
          'User-Agent': 'aicode-feedback-relay',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          title,
          body: body + meta,
          labels: ['in-app-feedback']
        })
      }
    );

    if (!res.ok) {
      const text = await res.text();
      return new Response(`github error: ${res.status} ${text}`, { status: 502 });
    }
    return new Response('accepted', { status: 202 });
  }
};
