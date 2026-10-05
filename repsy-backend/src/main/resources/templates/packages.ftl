<!DOCTYPE html>

<html>
  <head>
    <title>Simple index</title>
  </head>

  <body>
    <#list packages as package>
      <a href="${repoUri?html}/simple/${package.getNormalizedName()?html}/">${package.getName()?html}</a><br/>
    </#list>
  </body>
</html>
