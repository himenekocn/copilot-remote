import * as vscode from 'vscode';
import * as fs from 'fs/promises';
import * as https from 'https';
import * as crypto from 'crypto';

type ServiceAccount = { project_id: string; client_email: string; private_key: string };

export class FcmPush {
  private readonly secretKey = 'copilotRemote.fcm.tokens';
  private token?: { value: string; expiresAt: number };

  constructor(private readonly secrets: vscode.SecretStorage) {}

  async register(deviceToken: string) {
    if (!deviceToken.trim()) throw new Error('FCM token is empty');
    const tokens = new Set(JSON.parse(await this.secrets.get(this.secretKey) || '[]') as string[]);
    tokens.add(deviceToken.trim());
    await this.secrets.store(this.secretKey, JSON.stringify([...tokens]));
  }

  async unregister(deviceToken: string) {
    const tokens = new Set(JSON.parse(await this.secrets.get(this.secretKey) || '[]') as string[]);
    tokens.delete(deviceToken);
    await this.secrets.store(this.secretKey, JSON.stringify([...tokens]));
  }

  isConfigured() {
    return vscode.workspace.getConfiguration('copilotRemote.fcm').get<string>('serviceAccountPath', '').trim().length > 0;
  }

  async send(title: string, body: string) {
    const accountPath = vscode.workspace.getConfiguration('copilotRemote.fcm').get<string>('serviceAccountPath', '').trim();
    if (!accountPath) throw new Error('Firebase service account is not configured');
    const account = JSON.parse(await fs.readFile(accountPath, 'utf8')) as ServiceAccount;
    const accessToken = await this.accessToken(account);
    const tokens = JSON.parse(await this.secrets.get(this.secretKey) || '[]') as string[];
    const results = await Promise.allSettled(tokens.map(token => this.request(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`,
      'POST',
      { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
      JSON.stringify({ message: { token, data: { title, message: body.slice(0, 3500) }, android: { priority: 'high' } } }),
    )));
    const failures = results.filter((result): result is PromiseRejectedResult => result.status === 'rejected');
    if (failures.length) throw new Error(`${failures.length}/${results.length} FCM delivery request(s) failed: ${String(failures[0].reason)}`);
  }

  private async accessToken(account: ServiceAccount) {
    if (this.token && this.token.expiresAt > Date.now() + 60_000) return this.token.value;
    const now = Math.floor(Date.now() / 1000);
    const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
    const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })}`;
    const assertion = `${unsigned}.${crypto.sign('RSA-SHA256', Buffer.from(unsigned), account.private_key).toString('base64url')}`;
    const result = JSON.parse(await this.request('https://oauth2.googleapis.com/token', 'POST', { 'content-type': 'application/x-www-form-urlencoded' }, `grant_type=${encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer')}&assertion=${encodeURIComponent(assertion)}`));
    this.token = { value: result.access_token, expiresAt: Date.now() + Number(result.expires_in || 3600) * 1000 };
    return this.token.value;
  }

  private request(url: string, method: string, headers: Record<string, string>, body: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const req = https.request(url, { method, headers }, res => {
        const chunks: Buffer[] = [];
        res.on('data', chunk => chunks.push(Buffer.from(chunk)));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          if ((res.statusCode || 0) >= 200 && (res.statusCode || 0) < 300) resolve(text); else reject(new Error(`FCM HTTP ${res.statusCode}: ${text.slice(0, 300)}`));
        });
      });
      req.setTimeout(10_000, () => req.destroy(new Error('FCM request timed out')));
      req.on('error', reject); req.end(body);
    });
  }
}
