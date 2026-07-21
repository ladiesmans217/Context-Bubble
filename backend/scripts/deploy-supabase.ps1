param(
    [Parameter(Mandatory = $true)][string]$ProjectRef
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command supabase -ErrorAction SilentlyContinue)) {
    throw 'Install the Supabase CLI and run supabase login first.'
}

supabase link --project-ref $ProjectRef
supabase db push --linked
supabase functions deploy api --project-ref $ProjectRef --no-verify-jwt --import-map supabase/import_map.json
supabase functions deploy mcp-server --project-ref $ProjectRef --no-verify-jwt --import-map supabase/import_map.json

Write-Host 'Deployment complete. Configure the scheduled cleanup in Supabase Cron using migration 004 instructions.'
