Describe 'Disposable Kubernetes credentials' {
    It 'preserves creation, reuse, refusal and sanitized CLI boundaries' {
        & (Join-Path $PSScriptRoot '../k8s/check-disposable-secret.ps1')
    }
}

Describe 'Inventory architecture and publication boundaries' {
    It 'preserves both architectures, findings and refusal of inconsistent reports' {
        & (Join-Path $PSScriptRoot '../security/check-reports.ps1')
    }
}
