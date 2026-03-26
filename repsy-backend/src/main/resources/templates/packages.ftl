<!DOCTYPE html>

<html>
  <head>
    <title>Simple index</title>
  </head>

  <body>
    <#list packages as package>
      <a href="/pypi/${repoName}/simple/${package.getNormalizedName()}/">${package.getName()}</a><br/>
    </#list>
  </body>
</html>
