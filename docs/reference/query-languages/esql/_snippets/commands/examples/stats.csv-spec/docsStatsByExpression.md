% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

```esql
FROM employees
| STATS my_count = COUNT() BY LEFT(last_name, 1)
| SORT `LEFT(last_name, 1)`
```

| my_count:long | LEFT(last_name, 1):keyword |
| --- | --- |
| 2 | A |
| 11 | B |
| 5 | C |
| 5 | D |
| 2 | E |
| 4 | F |
| 4 | G |
| 6 | H |
| 2 | J |
| 3 | K |
| 5 | L |
| 12 | M |
| 4 | N |
| 1 | O |
| 7 | P |
| 5 | R |
| 13 | S |
| 4 | T |
| 2 | W |
| 3 | Z |
