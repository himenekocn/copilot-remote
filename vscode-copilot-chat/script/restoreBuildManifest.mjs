import fs from 'node:fs';

const manifestPath = new URL('../package.json', import.meta.url);
const lockPath = new URL('../package-lock.json', import.meta.url);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
const rootPackage = lock.packages?.[''];

if (process.argv.includes('--strip')) {
	delete manifest.scripts;
	delete manifest.dependencies;
	delete manifest.devDependencies;
	fs.writeFileSync(manifestPath, JSON.stringify(manifest));
	console.log('Restored production Copilot Chat manifest');
	process.exit(0);
}

if (!rootPackage?.dependencies || !rootPackage?.devDependencies) {
	throw new Error('package-lock.json does not contain the root dependency metadata');
}

manifest.scripts = {
	postinstall: 'tsx script/postinstall.ts',
	build: 'tsx .esbuild.ts',
};
manifest.dependencies = rootPackage.dependencies;
manifest.devDependencies = rootPackage.devDependencies;

fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, '\t')}\n`);
console.log('Restored Copilot Chat build metadata from package-lock.json');
