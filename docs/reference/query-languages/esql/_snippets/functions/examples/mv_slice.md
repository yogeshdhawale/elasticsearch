% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Examples**

```esql
row a = [1, 2, 2, 3]
| eval a1 = mv_slice(a, 1), a2 = mv_slice(a, 2, 3)
```

| a:integer | a1:integer | a2:integer |
| --- | --- | --- |
| [1, 2, 2, 3] | 2 | [2, 3] |

```esql
row a = [1, 2, 2, 3]
| eval a1 = mv_slice(a, -2), a2 = mv_slice(a, -3, -1)
```

| a:integer | a1:integer | a2:integer |
| --- | --- | --- |
| [1, 2, 2, 3] | 2 | [2, 2, 3] |


