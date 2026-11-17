# Pull request checks

The Tests workflow runs FileServer and Portal tests for pull requests targeting
`development`, pushes to that branch, and merge-queue entries. It does not
publish a container.

GitHub Actions alone cannot prevent a merge. After this workflow is committed,
create `development` from a commit containing it. In GitHub's branch protection
settings or a repository ruleset targeting `development`, require pull requests
and require both **FileServer tests** and **Portal tests** status checks to
pass before merging. Select the checks after they have run at least once.
Apply the rule to administrators and bypass roles too if those users must also
be blocked. If pull requests can also target `main`, protect `main` separately
with appropriate required checks.
