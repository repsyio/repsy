<!DOCTYPE html>

<html>
  <head>
    <title>Simple index</title>
  </head>

  <body>
    <#list packages as package>
      <a href="${repoUri}/simple/${package.getNormalizedName()}/">${package.getName()}</a><br/>
    </#list>
  </body>
</html>
