<!DOCTYPE html>

<html>
  <head>
    <title>Repsy | ${repository} | ${relativePath}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
      body {
        background : #fff;
      }
    </style>
  </head>

  <body>
    <header>
      <h1>${relativePath}</h1>
    </header>
    <hr/>
    <main>
    <pre id="contents">
<#list items as item><#if item.name == '../'><a href="../">../</a><#else><#assign truncatedName=item.name?truncate_c(48, '...')><a href="${item.name}" title="${item.name}">${truncatedName}</a><#list truncatedName?length..49 as x> </#list>${item.createdAt?datetime?string('yyyy-MM-dd HH:mm')}<#if item.size??>${item.size?string?left_pad(10)}</#if></#if>
</#list>
    </pre>
    </main>
    <hr/>
  </body>
</html>
